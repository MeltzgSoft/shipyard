(ns shipyard.e2e.detail-brush-test
  (:require [shipyard.persistence-fixture :as persisted]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as fixtures]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.paint-editor-test :as editor]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.loadout-fixture :as lf]
            [shipyard.scheme.db :as schemes]
            [shipyard.workspace.db :as workspace-db])
  (:import [com.microsoft.playwright Page Mouse$MoveOptions Mouse$DownOptions Mouse$UpOptions Dialog Route]
           [java.util.function Consumer]
           [com.microsoft.playwright.options MouseButton]))

(defn await-saved! [driver]
  (when-not (s/wait-until #(= "Details saved." (s/text driver "#brush-status")))
    (throw (ex-info "Stroke was not confirmed" {:status (s/text driver "#brush-status")}))))

(defn stroke! [driver x y]
  (let [mouse (.mouse ^Page (:page driver))]
    (.move mouse (double x) (double y)) (.down mouse) (.up mouse)))

(defn right-stroke! [driver x y]
  (let [mouse (.mouse ^Page (:page driver))]
    (.move mouse (double x) (double y))
    (.down mouse (doto (Mouse$DownOptions.) (.setButton MouseButton/RIGHT)))
    (.move mouse (+ (double x) 1) (double y) (doto (Mouse$MoveOptions.) (.setSteps 2)))
    (.up mouse (doto (Mouse$UpOptions.) (.setButton MouseButton/RIGHT)))))

(defn face-point [driver slot face]
  (let [centers (:face-centers (materials/slot driver slot))
        point (nth (filter :front? centers) face)
        origin (s/js driver "() => {let r=document.querySelector('canvas').getBoundingClientRect();return [r.x,r.y];}")]
    [(+ (first origin) (:x point)) (+ (second origin) (:y point))]))

(defn select-target! [driver _label path]
  (s/click! driver ".paint-tools button:text-is('Select')")
  (s/click! driver (str "#paint-target button[data-paint-target='" path "']"))
  (s/click! driver ".paint-tools button:text-is('Brush')")
  (s/wait-visible! driver "#paint-brush")
  (is (s/wait-until #(= path (s/js driver "() => document.querySelector('#paint-brush')?.elements.target.value")))))

(deftest visible-strokes-save-undo-erase-and-retry
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        store (:shipyard.scheme/db sys)]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly") (workspace/await-ship! driver)
      (s/click! driver "button:text-is('Paint assembly')")
      (s/click! driver ".paint-scheme-actions summary:text-is('New')")
      (s/wait-visible! driver "#paint-create")
      (s/fill-and-blur! driver "#paint-create input" "Detail proof")
      (s/click! driver "#paint-create button")
      (s/click! driver ".paint-tools button:text-is('Brush')")
      (s/wait-visible! driver "#paint-brush")
      (workspace/await-ship! driver)
      (.uncheck ^Page (:page driver) "#paint-brush input[name=cross-instances]")
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            center (face-point driver [] 0)
            masks #(get-in (schemes/snapshot! store) [:schemes id :scheme/details])
            paint! #(apply stroke! driver center)]
        (paint!)
        (when-not (s/wait-until #(= "Details saved." (s/text driver "#brush-status")))
          (s/screenshot-el! driver "body" (fs/file "/tmp/shipyard-brush-failure.png"))
          (throw (ex-info "Initial brush stroke was not saved" {:status (s/text driver "#brush-status")})))
        (let [painted (get-in (masks) [[] :faces])]
          (is (<= 1 (count painted) 6) "Only front-facing visible faces of the 12-triangle cube")
          (is (= #{[]} (set (keys (masks)))) "Repeated and nested instances stay untouched")
          (is (= painted (get-in (persisted/records! store :schemes) [:schemes id :scheme/details [] :faces])))
          (is (true? (:vertex-colors (materials/slot driver []))))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(false? (:vertex-colors (materials/slot driver [])))))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(true? (:vertex-colors (materials/slot driver [])))))
          (s/click! driver "#paint-brush button[value=undo]")
          (is (s/wait-until #(nil? (get (masks) []))))
          (s/click! driver "#paint-brush button[value=redo]")
          (is (s/wait-until #(= painted (get-in (masks) [[] :faces]))))
          (testing "Clear requires confirmation and can be undone"
            ;; Playwright dismisses unhandled dialogs: the first click is Cancel.
            (s/click! driver "button:text-is('Clear instance details')")
            (is (= painted (get-in (masks) [[] :faces])))
            (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ dialog] (.accept ^Dialog dialog))))
            (s/click! driver "button:text-is('Clear instance details')")
            (is (s/wait-until #(nil? (get (masks) []))))
            (s/click! driver "#paint-brush button[value=undo]")
            (is (s/wait-until #(= painted (get-in (masks) [[] :faces])))))
          (testing "Right-drag erases without changing the paint tool or camera"
            (let [camera (:camera (s/stats driver))]
              (apply right-stroke! driver center)
              (await-saved! driver)
              (is (empty? (get-in (masks) [[] :faces])))
              (is (= camera (:camera (s/stats driver))))
              (is (= "paint" (s/js driver "() => document.querySelector('#paint-brush input[name=mode]:checked').value")))
              (s/click! driver "#paint-brush button[value=undo]")
              (is (s/wait-until #(= painted (get-in (masks) [[] :faces]))))
              (s/click! driver "#paint-brush button[value=redo]")
              (is (s/wait-until #(empty? (get-in (masks) [[] :faces]))))))
          (testing "A failed database transaction preserves the stroke and supports retry"
            (is (s/wait-until #(and (empty? (:details (materials/slot driver [])))
                                    (false? (s/js driver "() => document.querySelector('#paint-brush').elements.radius.disabled")))))
            (let [before (schemes/snapshot! store)
                  revision (persisted/scheme-revision! store id Long/MAX_VALUE)]
              (try
                (paint!)
                (s/wait-visible! driver "#brush-status [role=alert]")
                (is (= before (schemes/snapshot! store)))
                (is (= before (persisted/records! store :schemes)))
                (finally (persisted/scheme-revision! store id revision)))
              (s/click! driver "button:text-is('Retry last stroke')")
              (is (s/wait-until #(= painted (get-in (masks) [[] :faces]))))
              (await-saved! driver)))
          (workspace/switch! driver "assembly")
          (workspace/switch! driver "paint") (workspace/await-ship! driver)
          (is (= (count painted) (count (:details (materials/slot driver [])))))
          (testing "Two separated areas retain distinct colors on one instance"
            (.uncheck ^Page (:page driver) "#paint-brush input[name=cross-instances]")
            (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
            (apply stroke! driver (face-point driver [] 0))
            (await-saved! driver)
            (editor/input! driver "#paint-brush input[name=brush-color]" "#00ff00" "input")
            (apply stroke! driver (face-point driver [] 3))
            (is (s/wait-until #(= #{[1.0 0.0 0.0] [0.0 1.0 0.0]} (set (map :base (vals (get-in (masks) [[] :faces]))))))))
          (testing "Repeated and nested copies have independent masks"
            (doseq [[label path slot] [["weapon · [[:weapon 0]]" "[[:weapon 0]]" [["weapon" 0]]]
                                       ["turret · [[:weapon 0] [:turret 0]]" "[[:weapon 0] [:turret 0]]" [["weapon" 0] ["turret" 0]]]]]
              (select-target! driver label path)
              (.uncheck ^Page (:page driver) "#paint-brush input[name=cross-instances]")
              (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
              ;; The weapon's +Z face is covered by its turret. Use a side face.
              (apply stroke! driver (face-point driver slot 2))
              (await-saved! driver))
            (is (= #{[] [[:weapon 0]] [[:weapon 0] [:turret 0]]} (set (keys (masks))))))
          (testing "The hull occludes a rear-mounted instance"
            (select-target! driver "weapon · [[:mirrored-weapon 0]]" "[[:mirrored-weapon 0]]")
            (.uncheck ^Page (:page driver) "#paint-brush input[name=cross-instances]")
            (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
            (let [before (schemes/snapshot! store)]
              (apply stroke! driver (face-point driver [["mirrored-weapon" 0]] 0))
              (is (= "No visible faces under the brush." (s/text driver "#brush-status")))
              (is (= before (schemes/snapshot! store)))))
          (testing "Alt-drag orbits without painting"
            (let [before (schemes/snapshot! store) camera (:camera (s/stats driver))
                  ^Page page (:page driver) mouse (.mouse page) [x y] (face-point driver [] 0)]
              (.down (.keyboard page) "Alt")
              (.move mouse x y) (.down mouse)
              (.move mouse (+ x 25) (+ y 20) (doto (Mouse$MoveOptions.) (.setSteps 5)))
              (.up mouse) (.up (.keyboard page) "Alt")
              (is (s/wait-until #(not= camera (:camera (s/stats driver)))))
              (is (= before (schemes/snapshot! store)))))
          (testing "Dragging commits one stroke, without moving the camera"
            (select-target! driver "hull · Hull" "[]")
            (.uncheck ^Page (:page driver) "#paint-brush input[name=cross-instances]")
            (let [camera (:camera (s/stats driver)) [x y] (face-point driver [] 0)
                  mouse (.mouse ^Page (:page driver))
                  sequence (s/js driver "() => Number(document.querySelector('#paint-brush').elements.sequence.value)")]
              (.move mouse x y) (.down mouse)
              (.move mouse (+ x 20) y (doto (Mouse$MoveOptions.) (.setSteps 4)))
              (.up mouse)
              (await-saved! driver)
              (is (< sequence (s/js driver "() => Number(document.querySelector('#paint-brush').elements.sequence.value)")))
              (is (= camera (:camera (s/stats driver))))))
          (s/screenshot-el! driver "body" (fs/file "/tmp/shipyard-detail-brush.png"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest cross-instance-drag-flushes-and-rolls-back-failed-parts
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        store (:shipyard.scheme/db sys) ^Page page (:page driver) mouse (.mouse page)
        state #(workspace-db/workspace! (:shipyard.workspace/db sys) :paint)]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly") (workspace/await-ship! driver)
      (s/click! driver "button:text-is('Paint assembly')")
      (s/click! driver ".paint-scheme-actions summary:text-is('New')")
      (s/fill-and-blur! driver "#paint-create input" "Cross instance")
      (s/click! driver "#paint-create button")
      (s/click! driver ".paint-tools button:text-is('Brush')")
      (s/wait-visible! driver "#paint-brush") (workspace/await-ship! driver)
      (is (true? (s/js driver "() => document.querySelector('#paint-brush').elements['cross-instances'].checked")))
      (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            masks #(get-in (schemes/snapshot! store) [:schemes id :scheme/details])
            [ax ay] (face-point driver [["weapon" 0]] 2)
            [bx by] (face-point driver [["weapon" 1]] 2)
            before (schemes/snapshot! store)]
        (.move mouse ax ay) (.down mouse)
        (when-not (s/wait-until #(:brush-pending (state)))
          (throw (ex-info "First chunk did not flush" {:status (s/text driver "#brush-status")})))
        (is (= before (schemes/snapshot! store)))
        (is (pos? (count (:layers (:brush-pending (state))))))
        (.move mouse bx by (doto (Mouse$MoveOptions.) (.setSteps 4)))
        (is (s/wait-until #(> (:next-part (:brush-pending (state)) 0) 1)))
        (is (= before (schemes/snapshot! store)))
        (is (re-find #"faces" (s/text driver "#paint-stroke-count")))
        (s/screenshot-el! driver "body" (fs/file "/tmp/shipyard-paint-live-stroke.png"))
        (.up mouse)
        (when-not (s/wait-until #(= "Details saved." (s/text driver "#brush-status")))
          (throw (ex-info "Cross-instance drag did not commit" {:status (s/text driver "#brush-status")})))
        (is (seq (get-in (masks) [[[:weapon 0]] :faces])))
        (is (seq (get-in (masks) [[[:weapon 1]] :faces])))
        (is (nil? (get (masks) [[:mirrored-weapon 0]])))
        (is (= 1 (count (get-in (state) [:brush-history :undo]))))
        (let [saved (schemes/snapshot! store)]
          (s/click! driver "#paint-brush button[value=undo]")
          (is (s/wait-until #(empty? (masks))))
          (s/click! driver "#paint-brush button[value=redo]")
          (is (s/wait-until #(= saved (schemes/snapshot! store))))
          (is (s/wait-until #(and (seq (:details (materials/slot driver [["weapon" 0]])))
                                  (false? (s/js driver "() => document.querySelector('#paint-brush').elements.radius.disabled"))))))
        (let [saved (schemes/snapshot! store) parts (atom 0)]
          (.route page "**/paint/stroke"
                  (reify Consumer
                    (accept [_ value]
                      (let [^Route route value]
                        (if (= 2 (swap! parts inc)) (.abort route) (.resume route))))))
          (editor/input! driver "#paint-brush input[name=brush-color]" "#0000ff" "input")
          (.move mouse ax ay) (.down mouse)
          (is (s/wait-until #(do (s/stats driver) (and (= 1 @parts) (:brush-pending (state))))))
          (.move mouse bx by (doto (Mouse$MoveOptions.) (.setSteps 4)))
          (s/wait-visible! driver "#brush-status [role=alert]")
          (.up mouse)
          (is (= saved (schemes/snapshot! store)))
          (is (= (into {} (map (fn [[k v]] [(keyword k) v]) (get-in (masks) [[[:weapon 0]] :faces])))
                 (update-vals (:details (materials/slot driver [["weapon" 0]])) #(-> % (update :base (partial mapv double)) (update :metalness double) (update :roughness double)))))
          (.unroute page "**/paint/stroke")
          (s/click! driver "button:text-is('Retry last stroke')")
          (await-saved! driver))
        (is (some #{[0.0 0.0 1.0]} (map :base (vals (get-in (masks) [[[:weapon 0]] :faces])))))
        (is (= 2 (count (get-in (state) [:brush-history :undo]))))
        (is (false? (s/js driver "() => document.querySelector('[data-workspace-mode=assembly]').disabled"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest large-visible-stroke-exceeds-the-former-face-cap
  (s/assert-bundle!)
  (let [started (fixture/start!
                 true (fn [root]
                        (fixture/library! root)
                        (with-open [out (io/output-stream (fs/file root (:hull fixture/ids) "unsupported.stl"))]
                          (.write out ^bytes (fixtures/->binary-stl (fixtures/uv-sphere 6.0 192 256))))
                        root))
        sys (:system started) driver (s/make-driver) store (:shipyard.scheme/db sys)]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft {:revision 1 :hull (:hull fixture/ids) :assignments {}}
             :root (str (:root started)))
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly")
      (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
      (s/click! driver "button:text-is('Paint assembly')")
      (s/click! driver ".paint-scheme-actions summary:text-is('New')")
      (s/fill-and-blur! driver "#paint-create input" "Large stroke")
      (s/click! driver "#paint-create button")
      (s/click! driver ".paint-tools button:text-is('Brush')")
      (s/wait-visible! driver "#paint-brush")
      (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
      (editor/input! driver "#paint-brush input[name=radius]" "100" "input")
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            center (s/js driver "() => {const c=document.querySelector('canvas').getBoundingClientRect(),i=document.querySelector('.paint-editor').getBoundingClientRect();return [c.x+(c.width-i.width-28)/2,c.y+c.height/2];}")
            began (System/nanoTime) geometries (:geometries (s/stats driver))]
        (apply stroke! driver center)
        (when-not (s/wait-until #(= "Details saved." (s/text driver "#brush-status")))
          (throw (ex-info "Large stroke failed" {:status (s/text driver "#brush-status")})))
        (let [layer (get-in (schemes/snapshot! store) [:schemes id :scheme/details []])]
          (is (> (count (:faces layer)) 1024))
          (is (= layer (get-in (persisted/records! store :schemes) [:schemes id :scheme/details []])))
          (is (<= (:geometries (s/stats driver)) (inc geometries)))
          (spit "/tmp/shipyard-large-stroke.edn"
                (pr-str {:triangles (* 2 256 191) :painted-faces (count (:faces layer))
                         :elapsed-ms (/ (- (System/nanoTime) began) 1e6)
                         :store-bytes (fs/size (fs/path (get-in store [:store :directory]) "data.mdb")) :renderer :swiftshader
                         :geometries-before geometries :geometries-after (:geometries (s/stats driver))}))))
      (finally (s/quit! driver) (fixture/stop! started)))))
