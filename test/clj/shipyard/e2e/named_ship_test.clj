(ns shipyard.e2e.named-ship-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as classes]
            [shipyard.ship.db :as ships]
            [shipyard.scheme.db :as schemes]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.paint-editor-test :as editor]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.persistence-fixture :as persisted])
  (:import [com.microsoft.playwright Page Dialog]
           [java.util.function Consumer]))

(defn tab! [driver tab]
  (s/click! driver (str ".ship-inspector nav button:text-is('" tab "')"))
  (is (s/wait-until #(= tab (s/text driver ".ship-inspector nav button[aria-current=page]")))))

(defn create! [driver name]
  (when-not (s/js driver "() => document.querySelector('#paint-create').parentElement.open")
    (s/click! driver ".paint-scheme summary:text-is('New named ship')"))
  (s/fill-and-blur! driver "#paint-create input[name=name]" name)
  (s/click! driver "#paint-create button")
  (s/wait-visible! driver "#paint-material"))

(defn scheme-layer! [driver name]
  (let [selector (str "#scheme-layer button[aria-label='Edit " name " color']")
        selected? #(= "true" (s/js driver (str "() => document.querySelector(\"" selector "\")?.getAttribute('aria-pressed')")))]
    (when-not (selected?)
      (s/click! driver (str selector " .scheme-layer__swatch"))
      (is (s/wait-until selected?)))
    (is (= (str name " material") (s/text driver "#scheme-material-label")))))

(deftest class-cards-fleet-palettes-and-custom-hulls
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        class-db (:shipyard.loadout/db sys) ship-db (:shipyard.ship/db sys) scheme-db (:shipyard.scheme/db sys)
        class {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}
        second-class (assoc class :loadout/id (random-uuid) :loadout/name "Scout" :loadout/slots {})]
    (try
      (classes/put! class-db class :create)
      (classes/put! class-db second-class :create)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "ships")
      (is (zero? (s/js driver "() => document.querySelectorAll('[data-workspace-mode=paint]').length")))
      (s/open-class! driver "Cruiser")
      (workspace/await-ship! driver)
      (tab! driver "Schemes")
      (s/fill-and-blur! driver "#scheme-create input[name=name]" "Blue fleet")
      (s/click! driver "#scheme-create button")
      (s/wait-visible! driver "#scheme-material")
      (s/js driver "() => {window.paletteResponses=[];document.body.addEventListener('htmx:afterRequest',e=>{if(/\\/schemes\\/(layer|material)$/.test(e.detail.xhr.responseURL))window.paletteResponses.push(e.detail.xhr.responseText.length);});}")
      (is (= 2 (s/js driver "() => document.querySelectorAll('#scheme-layer button').length")))
      (is (= 1 (s/js driver "() => document.querySelectorAll('#scheme-layer [aria-pressed=true]').length")))
      (editor/input! driver "#scheme-material input[name=base]" "#0000ff" "input")
      (editor/input! driver "#scheme-material input[name=glow]" "0.7" "input")
      (is (s/wait-until #(= 0.7 (:glow (materials/slot driver [])))) "Glow previews before saving")
      (is (= "0000ff" (:emissive (materials/slot driver []))))
      (is (s/wait-until #(true? (get-in (s/stats driver) [:glow :active?]))))
      (is (<= 1 (get-in (s/stats driver) [:glow :source-count]) 8))
      (s/screenshot-el! driver "#viewport" (java.io.File. "/tmp/shipyard-glow-halo.png"))
      (is (s/wait-until #(= "0000ff" (:color (materials/slot driver [])))))
      (is (= ["rgb(0, 0, 255)" "rgb(0, 0, 255)"]
             (s/js driver "() => [...document.querySelectorAll('.scheme-layer__swatch')].map(s=>getComputedStyle(s).backgroundColor)"))
          "Primary and inherited swatches track the live color picker")
      (editor/input! driver "#scheme-material input[name=base]" "#0000ff" "change")
      (is (s/wait-until #(= [0.0 0.0 1.0] (-> (schemes/snapshot! scheme-db) :schemes vals first :scheme/layers (get "Primary") :base))))
      (scheme-layer! driver "Secondary")
      (is (= 0.7 (-> (persisted/records! scheme-db :schemes) :schemes vals first :scheme/layers (get "Primary") :glow)))
      (editor/input! driver "#scheme-material input[name=base]" "#d4af37" "input")
      (editor/input! driver "#scheme-material input[name=metalness]" "0.8" "input")
      (editor/input! driver "#scheme-material input[name=roughness]" "0.25" "input")
      (s/click! driver "#scheme-material button")
      (is (s/wait-until #(= 0.8 (-> (schemes/snapshot! scheme-db) :schemes vals first :scheme/layers (get "Secondary") :metalness))))
      (is (= ["rgb(0, 0, 255)" "rgb(212, 175, 55)"]
             (s/js driver "() => [...document.querySelectorAll('.scheme-layer__swatch')].map(s=>getComputedStyle(s).backgroundColor)")))
      (scheme-layer! driver "Primary")
      (is (= "#0000ff" (s/js driver "() => document.querySelector('#scheme-material').elements.base.value")))
      (scheme-layer! driver "Secondary")
      (is (= ["#d4af37" "0.8" "0.25"]
             (s/js driver "() => {const f=document.querySelector('#scheme-material').elements;return [f.base.value,f.metalness.value,f.roughness.value];}")))
      (s/click! driver "#scheme-presets button:text-is('Save current color')")
      (s/wait-visible! driver "button[aria-label='Use #d4af37']")
      ;; The real spectrum updates the same hex field and saves on release.
      (s/click! driver ".color-spectrum")
      (is (s/wait-until #(not= "#d4af37" (s/js driver "() => document.querySelector('#scheme-material').elements.base.value"))))
      (s/click! driver "button[aria-label='Use #d4af37']")
      (is (s/wait-until #(= "#d4af37" (s/js driver "() => document.querySelector('#scheme-material').elements.base.value"))))
      (is (= ["0.8" "0.25"] (s/js driver "() => {const f=document.querySelector('#scheme-material').elements;return [f.metalness.value,f.roughness.value];}"))
          "A saved color changes color only, retaining the selected layer finish")
      (is (= "0.7" (s/js driver "() => document.querySelector('#scheme-material').elements.glow.value")) "Color presets retain glow")
      (is (every? #(< % 20000) (s/js driver "() => window.paletteResponses"))
          "Layer and material requests never retransmit the ship")
      (s/screenshot-el! driver "#detail" (java.io.File. "/tmp/shipyard-scheme-layer-list.png"))
      (scheme-layer! driver "Primary")
      (s/open-class! driver "Scout")
      (tab! driver "Schemes")
      (is (s/wait-until #(= "0000ff" (:color (materials/slot driver [])))))
      (is (empty? (:ships (ships/snapshot! ship-db))))
      (s/open-class! driver "Cruiser")
      (tab! driver "Paint")
      (create! driver "Resolute")
      (workspace/await-ship! driver)
      (is (zero? (s/count-els driver "#paint-target button[value^='role/']")))
      (s/click! driver ".paint-write button:text-is('Layer')")
      (is (s/wait-until #(= "layer/Primary" (s/js driver "() => document.querySelector('#paint-material').elements.target.value"))))
      (s/click! driver ".paint-write button:text-is('Instance')")
      (is (s/wait-until #(= "[]" (s/js driver "() => document.querySelector('#paint-material').elements.target.value"))))
      (let [id (-> (ships/snapshot! ship-db) :ships keys first)]
        (is (= (:loadout/id class) (get-in (ships/snapshot! ship-db) [:ships id :ship/class])))
        (is (= "Ship Browser" (s/text driver ".masthead__mode--active")))
        (editor/input! driver "#paint-material input[name=base]" "#ff0000" "input")
        (editor/input! driver "#paint-material input[name=glow]" "0.2" "input")
        (editor/input! driver "#paint-material input[name=base]" "#ff0000" "change")
        (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status"))))
        (is (= "ff0000" (:color (materials/slot driver []))))
        (is (= "0000ff" (:color (materials/slot driver [["weapon" 0]]))))
        (is (= 0.2 (:glow (materials/slot driver []))))
        (is (= 0.7 (:glow (materials/slot driver [["weapon" 0]]))))
        (create! driver "Intrepid")
        (is (s/wait-until #(= "0000ff" (:color (materials/slot driver [])))))
        (is (= 2 (count (:ships (ships/snapshot! ship-db)))))
        (s/ship-table! driver)
        (s/click! driver ".ship-card:has(.ship-table__row[aria-label='Open class Cruiser']) summary")
        (is (s/wait-until #(= 2 (s/count-els driver "[data-ship-id]"))))
        (s/click! driver (str "[data-ship-id='" id "'] button"))
        (is (s/wait-until #(= "ff0000" (:color (materials/slot driver [])))))
        (let [before (materials/slot driver []) camera (:camera (s/stats driver))]
          (tab! driver "Schemes")
          (is (= (:uuid before) (:uuid (materials/slot driver []))))
          (is (= camera (:camera (s/stats driver)))))
        (is (s/wait-until #(= "0000ff" (:color (materials/slot driver [])))))
        (editor/input! driver "#scheme-material input[name=base]" "#00ff00" "change")
        (is (s/wait-until #(= [0.0 1.0 0.0] (-> (schemes/snapshot! scheme-db) :schemes vals first :scheme/layers (get "Primary") :base))))
        (tab! driver "Paint")
        (is (s/wait-until #(= "ff0000" (:color (materials/slot driver [])))))
        (is (= 0.2 (:glow (materials/slot driver []))) "Custom glow survives scheme edits")
        (is (= "00ff00" (:color (materials/slot driver [["weapon" 0]]))))
        (s/select-option! driver "#paint-scheme select" "No scheme")
        (is (s/wait-until #(= "9aa4af" (:color (materials/slot driver [["weapon" 0]])))))
        (is (= "ff0000" (:color (materials/slot driver []))))
        (s/select-option! driver "#paint-scheme select" "Blue fleet")
        (is (s/wait-until #(= "00ff00" (:color (materials/slot driver [["weapon" 0]])))))
        (s/click! driver ".paint-scheme summary:text-is('Manage ship')")
        (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ dialog] (.accept ^Dialog dialog))))
        (s/click! driver "#paint-reset button")
        (is (s/wait-until #(= "00ff00" (:color (materials/slot driver [])))))
        (is (empty? (get-in (ships/snapshot! ship-db) [:ships id :ship/paint :paint/instances])))
        (is (= 0.7 (:glow (materials/slot driver []))) "Reset restores scheme glow")
        (is (= (ships/snapshot! ship-db) (persisted/records! ship-db :ships)))
        (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-consolidated-browser.png")))
      (finally (s/quit! driver) (fixture/stop! started)))))
