(ns shipyard.e2e.color-picker-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.ship.db :as ships]
            [shipyard.e2e.support :as s]
            [shipyard.paint.transforms :as paint]
            [shipyard.scheme.color :as color]
            [shipyard.scheme.db :as schemes]))

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

(deftest customize-brush-spectrum-and-presets
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ship-db (:shipyard.ship/db sys)]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (lf/save-class! sys)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly")
      (workspace/await-ship! driver)
      (named/tab! driver "Customize")
      (named/create! driver "Picker proof")
      (workspace/await-ship! driver)
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :ship-id])
            record #(get-in (ships/snapshot! ship-db) [:ships id :ship/paint])]
        (is (zero? (s/count-els driver ".paint-editor input[type=color]")))
        (testing "Brush picker changes the next stroke without changing its fleet scheme"
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
            (s/fill-and-blur! driver "#paint-brush input[name=brush-color]" "#d4af37")
            (s/click! driver "#scheme-presets button:text-is('Save current color')")
            (s/wait-visible! driver "button[aria-label='Use #d4af37']")
            (s/fill-and-blur! driver "#paint-brush input[name=brush-color]" "#000000")
            (s/click! driver "button[aria-label='Use #d4af37']")
            (is (= "#d4af37" (s/js driver "() => document.querySelector('#paint-brush').elements['brush-color'].value")))
            (is (= before (record)))
            (apply brush/stroke! driver (brush/face-point driver [] 0))
            (brush/await-saved! driver)
            (is (seq (:paint/details (record))))
            (is (= #{"#d4af37"} (set (for [layer (vals (:paint/details (record))) detail (vals (:faces layer))]
                                       (paint/color-hex (:base detail))))))
            (is (= (:ship/scheme (get-in (ships/snapshot! ship-db) [:ships id])) nil)))
          (s/screenshot-el! driver ".stage__detail" (java.io.File. "/tmp/shipyard-paint-picker.png"))))
      (finally (s/quit! driver) (fixture/stop! started)))))
