(ns shipyard.e2e.color-picker-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.ship.db :as ships]
            [shipyard.e2e.support :as s]
            [shipyard.paint.transforms :as paint]
            [shipyard.scheme.color :as color]
            [shipyard.scheme.db :as schemes])
  (:import [com.microsoft.playwright Page]))

(defn- picker [driver]
  (s/js driver "() => {const f=document.querySelector('#scheme-material').elements; return {hex:f.base.value,hsv:[+f.hue.value,+f.saturation.value,+f.brightness.value]};}"))

(defn- save! [driver edit!]
  (let [sequence #(s/js driver "() => +document.querySelector('#scheme-status').dataset.sequence")
        before (sequence)]
    (edit!)
    (is (s/wait-until #(and (> (sequence) before)
                            (s/js driver "() => !document.querySelector('#scheme-material').elements.base.disabled")))
        (pr-str {:before before :after (sequence)
                 :form (s/js driver "() => [...document.querySelector('#scheme-material').elements].map(e=>[e.name,e.value,e.validationMessage])")
                 :errors (s/js driver "() => [...document.querySelectorAll('[role=alert]')].map(e=>e.textContent)")}))))

(defn- hue! [driver fraction]
  (s/scroll-into-view! driver ".color-hue")
  (let [{:keys [x y width height]} (s/bounds driver ".color-hue")]
    (s/click-point! driver (+ x (* width fraction)) (+ y (/ height 2)))))

(deftest spectrum-position-survives-save-responses
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        scheme-db (:shipyard.scheme/db sys)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-assembly! driver)
      (named/tab! driver "Schemes")
      (s/fill-and-blur! driver "#scheme-create input[name=name]" "Spectrum fleet")
      (s/click! driver "#scheme-create button")
      (s/wait-visible! driver "#scheme-material")
      (save! driver #(s/fill-and-blur! driver "#scheme-material input[name=base]" "#808080"))
      (save! driver #(hue! driver 0.4))
      (let [{:keys [hex hsv]} (picker driver) hue (first hsv)]
        (is (= "#808080" hex))
        (is (< 125 hue 165) "Gray retains the selected hue after autosave")
        (save! driver #(s/click! driver ".color-spectrum"))
        (is (= hue (first (:hsv (picker driver)))))
        (is (= (:hex (picker driver)) (color/hsv->hex (:hsv (picker driver)))))
        (is (= (color/hsv->hex (:hsv (picker driver)))
               (-> (schemes/snapshot! scheme-db) :schemes vals first
                   :scheme/layers (get "Primary") :base paint/color-hex))))
      ;; Drag below the square while it owns pointer capture: brightness reaches
      ;; zero, but the chosen saturation and hue must remain available.
      (save! driver #(let [{:keys [x y width height]} (s/bounds driver ".color-spectrum")]
                       (s/drag! driver [(+ x (* width 0.7)) (+ y (/ height 2))]
                                [(+ x (* width 0.7)) (+ y height 5)])))
      (is (= "#000000" (:hex (picker driver))))
      (is (< 0.65 (second (:hsv (picker driver))) 0.75))
      (save! driver #(hue! driver 0.8))
      (let [black (picker driver)]
        (is (= "#000000" (:hex black)))
        (is (< 270 (first (:hsv black)) 310))
        (is (< 0.65 (second (:hsv black)) 0.75))
        (save! driver #(s/click! driver "#scheme-material input[name=glow]"))
        (is (< 0.4 (-> (schemes/snapshot! scheme-db) :schemes vals first :scheme/layers (get "Primary") :glow) 0.6))
        (is (= black (picker driver)) "An unrelated material save preserves the picker position"))
      (named/scheme-layer! driver "Secondary")
      (is (= [0 0 0] (:hsv (picker driver)))
          "Another layer does not inherit black's ambiguous coordinates")
      (save! driver #(s/fill-and-blur! driver "#scheme-material input[name=base]" "#00ff00"))
      (is (= [120 1 1] (:hsv (picker driver)))
          "Hex entry still controls the spectrum")
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest paint-and-detail-brush-share-spectrum-and-presets
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ship-db (:shipyard.ship/db sys)]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (lf/save-class! sys)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly")
      (workspace/await-ship! driver)
      (named/tab! driver "Paint")
      (named/create! driver "Picker proof")
      (workspace/await-ship! driver)
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :ship-id])
            record #(get-in (ships/snapshot! ship-db) [:ships id :ship/paint])
            saved-hex #(some-> (get-in (record) [:paint/instances [] :material :base]) (paint/color-hex))
            hex #(s/js driver "() => document.querySelector('#paint-material').elements.base.value")
            mouse (.mouse ^Page (:page driver))]
        (is (zero? (s/count-els driver ".paint-editor input[type=color]")))
        (s/fill-and-blur! driver "#paint-material input[name=base]" "#ff0000")
        (is (s/wait-until #(= "#ff0000" (saved-hex))))
        (testing "Dragging previews locally and commits only on release"
          (s/scroll-into-view! driver "#paint-material .color-spectrum")
          (let [{:keys [x y width height]} (s/bounds driver "#paint-material .color-spectrum")
                before (record)]
            (.move mouse (+ x (* width 0.65)) (+ y (* height 0.3)))
            (.down mouse)
            (is (s/wait-until #(not= "#ff0000" (hex))))
            (is (= (subs (hex) 1) (:color (materials/slot driver []))))
            (is (= before (record)))
            (.up mouse)
            (is (s/wait-until #(= (hex) (saved-hex))))))
        (testing "Hue and keyboard edits save through the ordinary material form"
          (s/scroll-into-view! driver "#paint-material .color-hue")
          (let [{:keys [x y width height]} (s/bounds driver "#paint-material .color-hue")
                before (hex)]
            (s/click-point! driver (+ x (* width 0.4)) (+ y (/ height 2)))
            (is (not= before (hex)))
            (is (s/wait-until #(= (hex) (saved-hex)))))
          (let [before (hex)]
            (.press (.locator ^Page (:page driver) "#paint-material .color-spectrum") "Shift+ArrowDown")
            (is (not= before (hex)))
            (is (s/wait-until #(= (hex) (saved-hex))))))
        (testing "Presets retain finishes and are shared with Schemes"
          (s/fill-and-blur! driver "#paint-material input[name=base]" "#d4af37")
          (is (s/wait-until #(= "#d4af37" (saved-hex))))
          (s/click! driver "#scheme-presets button:text-is('Save current color')")
          (s/wait-visible! driver "button[aria-label='Use #d4af37']")
          (let [finish (dissoc (get-in (record) [:paint/instances [] :material]) :base)]
            (s/fill-and-blur! driver "#paint-material input[name=base]" "#000000")
            (is (s/wait-until #(= "#000000" (saved-hex))))
            (s/click! driver "button[aria-label='Use #d4af37']")
            (is (s/wait-until #(= "#d4af37" (saved-hex))))
            (is (= finish (dissoc (get-in (record) [:paint/instances [] :material]) :base))))
          (named/tab! driver "Schemes")
          (s/fill-and-blur! driver "#scheme-create input[name=name]" "Shared preset fleet")
          (s/click! driver "#scheme-create button")
          (s/wait-visible! driver "#scheme-material")
          (s/click! driver "button[aria-label='Use #d4af37']")
          (is (s/wait-until #(= "#d4af37" (-> (schemes/snapshot! (:shipyard.scheme/db sys)) :schemes vals first
                                              :scheme/layers (get "Primary") :base paint/color-hex))))
          (named/tab! driver "Paint"))
        (testing "Brush picker changes the next stroke without creating material overrides"
          (s/click! driver ".paint-tools button:text-is('Brush')")
          (s/wait-visible! driver "#paint-brush")
          (let [before (record)]
            (s/fill-and-blur! driver "#paint-brush input[name=brush-color]" "#bad")
            (apply brush/stroke! driver (brush/face-point driver [] 0))
            (is (= "Enter a six-digit detail hex color before painting." (s/text driver "#brush-status")))
            (is (= before (record)))
            (s/fill-and-blur! driver "#paint-brush input[name=brush-color]" "#00ff00")
            (is (= "120" (s/js driver "() => document.querySelector('#paint-brush').elements.hue.value")))
            (s/scroll-into-view! driver "#paint-brush .color-hue")
            (let [{:keys [x y width height]} (s/bounds driver "#paint-brush .color-hue")]
              (s/click-point! driver (+ x (* width 0.8)) (+ y (/ height 2))))
            (s/click! driver "#paint-brush .color-spectrum")
            (is (= before (record)))
            (s/click! driver "button[aria-label='Use #d4af37']")
            (is (= "#d4af37" (s/js driver "() => document.querySelector('#paint-brush').elements['brush-color'].value")))
            (is (= before (record)))
            (apply brush/stroke! driver (brush/face-point driver [] 0))
            (brush/await-saved! driver)
            (is (seq (:paint/details (record))))
            (is (= #{"#d4af37"} (set (for [layer (vals (:paint/details (record))) detail (vals (:faces layer))]
                                       (paint/color-hex (:base detail))))))
            (is (= (:paint/instances before) (:paint/instances (record)))))
          (s/screenshot-el! driver ".stage__detail" (java.io.File. "/tmp/shipyard-paint-picker.png"))))
      (finally (s/quit! driver) (fixture/stop! started)))))
