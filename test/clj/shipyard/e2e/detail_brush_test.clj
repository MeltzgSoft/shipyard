(ns shipyard.e2e.detail-brush-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.paint-editor-test :as editor]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.loadout-fixture :as lf]
            [shipyard.scheme.db :as schemes])
  (:import [com.microsoft.playwright Page Mouse$MoveOptions Dialog]
           [java.util.function Consumer]))

(defn stroke! [driver x y]
  (let [mouse (.mouse ^Page (:page driver))]
    (.move mouse (double x) (double y)) (.down mouse) (.up mouse)))

(defn face-point [driver slot face]
  (let [centers (:face-centers (materials/slot driver slot))
        point (nth (filter :front? centers) face)
        origin (s/js driver "() => {let r=document.querySelector('canvas').getBoundingClientRect();return [r.x,r.y];}")]
    [(+ (first origin) (:x point)) (+ (second origin) (:y point))]))

(defn select-target! [driver label path]
  (s/select-option! driver "#paint-target select" label)
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
      (s/wait-visible! driver "#paint-create")
      (s/fill-and-blur! driver "#paint-create input" "Detail proof")
      (s/click! driver "#paint-create button")
      (s/wait-visible! driver "#paint-brush")
      (workspace/await-ship! driver)
      (s/click! driver "#paint-brush input[name=enabled]")
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            center (s/js driver "() => {let r=document.querySelector('canvas').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2];}")
            masks #(get-in (schemes/snapshot! store) [:schemes id :scheme/details])
            paint! #(apply stroke! driver center)]
        (paint!)
        (is (s/wait-until #(= "Details saved." (s/text driver "#brush-status"))))
        (let [painted (get-in (masks) [[] :faces])]
          (is (<= 1 (count painted) 6) "Only front-facing visible faces of the 12-triangle cube")
          (is (= #{[]} (set (keys (masks)))) "Repeated and nested instances stay untouched")
          (is (= painted (get-in (schemes/snapshot! (schemes/open! (:file store))) [:schemes id :scheme/details [] :faces])))
          (is (true? (:vertex-colors (materials/slot driver []))))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(false? (:vertex-colors (materials/slot driver [])))))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(true? (:vertex-colors (materials/slot driver [])))))
          (s/click! driver "button:text-is('Undo detail stroke')")
          (is (s/wait-until #(nil? (get (masks) []))))
          (s/click! driver "button:text-is('Redo detail stroke')")
          (is (s/wait-until #(= painted (get-in (masks) [[] :faces]))))
          (testing "Clear requires confirmation and can be undone"
            ;; Playwright dismisses unhandled dialogs: the first click is Cancel.
            (s/click! driver "button:text-is('Clear instance details')")
            (is (= painted (get-in (masks) [[] :faces])))
            (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ dialog] (.accept ^Dialog dialog))))
            (s/click! driver "button:text-is('Clear instance details')")
            (is (s/wait-until #(nil? (get (masks) []))))
            (s/click! driver "button:text-is('Undo detail stroke')")
            (is (s/wait-until #(= painted (get-in (masks) [[] :faces])))))
          (s/select-option! driver "#paint-brush select[name=mode]" "Erase to base")
          (paint!)
          (is (s/wait-until #(empty? (get-in (masks) [[] :faces]))))
          (s/select-option! driver "#paint-brush select[name=mode]" "Paint")
          (testing "A real write failure keeps a preview and a retryable stroke"
            (let [before (schemes/snapshot! store) file (:file store) backup (fs/path (:temp started) "before-brush.edn")]
              (fs/move file backup) (fs/create-dirs file)
              (paint!)
              (s/wait-visible! driver "#brush-status [role=alert]")
              (is (= before (schemes/snapshot! store)))
              (fs/delete-if-exists file) (fs/move backup file)
              (s/click! driver "button:text-is('Retry last stroke')")
              (is (s/wait-until #(= "Details saved." (s/text driver "#brush-status"))))
              (is (= painted (get-in (masks) [[] :faces])))))
          (workspace/switch! driver "assembly")
          (workspace/switch! driver "paint") (workspace/await-ship! driver)
          (is (= (count painted) (count (:details (materials/slot driver [])))))
          (testing "Two separated areas retain distinct colors on one instance"
            (s/click! driver "#paint-brush input[name=enabled]")
            (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
            (apply stroke! driver (face-point driver [] 0))
            (is (s/wait-until #(= "Details saved." (s/text driver "#brush-status"))))
            (editor/input! driver "#paint-brush input[name=brush-color]" "#00ff00" "input")
            (apply stroke! driver (face-point driver [] 3))
            (is (s/wait-until #(= #{[1.0 0.0 0.0] [0.0 1.0 0.0]} (set (vals (get-in (masks) [[] :faces])))))))
          (testing "Repeated and nested copies have independent masks"
            (doseq [[label path slot] [["weapon · [[:weapon 0]]" "[[:weapon 0]]" [["weapon" 0]]]
                                       ["turret · [[:weapon 0] [:turret 0]]" "[[:weapon 0] [:turret 0]]" [["weapon" 0] ["turret" 0]]]]]
              (select-target! driver label path)
              (s/click! driver "#paint-brush input[name=enabled]")
              (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
              ;; The weapon's +Z face is covered by its turret. Use a side face.
              (apply stroke! driver (face-point driver slot 2))
              (is (s/wait-until #(= "Details saved." (s/text driver "#brush-status")))))
            (is (= #{[] [[:weapon 0]] [[:weapon 0] [:turret 0]]} (set (keys (masks))))))
          (testing "The hull occludes a rear-mounted instance"
            (select-target! driver "weapon · [[:mirrored-weapon 0]]" "[[:mirrored-weapon 0]]")
            (s/click! driver "#paint-brush input[name=enabled]")
            (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
            (let [before (schemes/snapshot! store)]
              (apply stroke! driver (face-point driver [["mirrored-weapon" 0]] 0))
              (is (= "No visible faces of the selected instance under the brush." (s/text driver "#brush-status")))
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
            (s/click! driver "#paint-brush input[name=enabled]")
            (let [camera (:camera (s/stats driver)) [x y] (face-point driver [] 0)
                  mouse (.mouse ^Page (:page driver))
                  sequence (s/js driver "() => Number(document.querySelector('#paint-brush').elements.sequence.value)")]
              (.move mouse x y) (.down mouse)
              (.move mouse (+ x 20) y (doto (Mouse$MoveOptions.) (.setSteps 4)))
              (.up mouse)
              (is (s/wait-until #(= "Details saved." (s/text driver "#brush-status"))))
              (is (= (inc sequence) (s/js driver "() => Number(document.querySelector('#paint-brush').elements.sequence.value)")))
              (is (= camera (:camera (s/stats driver))))))
          (s/screenshot-el! driver "body" (fs/file "/tmp/shipyard-detail-brush.png"))))
      (finally (s/quit! driver) (fixture/stop! started)))))
