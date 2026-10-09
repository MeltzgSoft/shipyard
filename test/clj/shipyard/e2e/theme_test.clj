(ns shipyard.e2e.theme-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.e2e.support :as s]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as classes]
            [shipyard.scheme.db :as schemes]
            [shipyard.ship.db :as ships])
  (:import [com.microsoft.playwright Page]))

(defn- themed! [driver selector]
  (s/wait-visible! driver selector)
  (is (= ["rgb(35, 42, 51)" "rgb(223, 228, 234)" "dark"]
         (s/js driver (str "() => {const s=getComputedStyle(document.querySelector(" (pr-str selector) "));return [s.backgroundColor,s.color,s.colorScheme]}")))
      (str selector " uses the shared dark surface and readable text")))

(defn- native-theme! [driver selector]
  (s/wait-visible! driver selector)
  (is (= ["rgb(111, 177, 216)" "dark" "auto"]
         (s/js driver (str "() => {const s=getComputedStyle(document.querySelector(" (pr-str selector) "));return [s.accentColor,s.colorScheme,s.appearance]}")))
      (str selector " retains a native control with the shared accent")))

(deftest shared-theme-survives-selection-and-editor-swaps
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        scheme-db (:shipyard.scheme/db sys) ship-db (:shipyard.ship/db sys)]
    (try
      (classes/put! (:shipyard.loadout/db sys)
                    {:loadout/id (random-uuid) :loadout/name "Theme Cruiser"
                     :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments} :create)
      (s/go! driver (s/base-url sys))
      (themed! driver "input[placeholder='Optional group name']")
      (themed! driver "button[data-variant-group]")
      (native-theme! driver "#part-select-matching")
      (.press ^Page (:page driver) "input[placeholder='Optional group name']" "Tab")
      (is (= [true "rgb(111, 177, 216)" "2px"]
             (s/js driver "() => {const e=document.querySelector('[data-variant-group]'),s=getComputedStyle(e);return [e===document.activeElement,s.outlineColor,s.outlineWidth]}"))
          "Keyboard navigation gives the grouping action a visible accent outline")
      (s/check! driver "#part-select-matching")
      (is (s/wait-until #(pos? (s/js driver "() => document.querySelectorAll('[data-bulk-select]:checked').length"))))
      (themed! driver "button[data-variant-group]")
      (s/click! driver "button:text-is('Clear selection')")
      (s/open-class! driver "Theme Cruiser")
      (s/wait-visible! driver ".assembly__save")
      (themed! driver ".assembly__save input[name=name]")
      (themed! driver ".assembly__save button")
      (is (= "rgb(111, 177, 216)" (s/js driver "() => getComputedStyle(document.querySelector('.assembly__hull button')).backgroundColor"))
          "The primary assembly action retains its accent surface")
      (themed! driver ".assembly__mount-actions button")
      (themed! driver "form[action='/assembly/paint'] button")
      (named/tab! driver "Schemes")
      (themed! driver ".scheme-editor select[name=id]")
      (themed! driver "#scheme-create input[name=name]")
      (s/fill-and-blur! driver "#scheme-create input[name=name]" "Theme Fleet")
      (s/click! driver "#scheme-create button")
      (s/wait-visible! driver "#scheme-material")
      (is (= "Theme Fleet" (-> (schemes/snapshot! scheme-db) :schemes vals first :scheme/name)))
      (native-theme! driver "#scheme-material input[name=metalness]")
      (is (s/js driver "() => getComputedStyle(document.querySelector('.color-hue')).backgroundImage.includes('linear-gradient')")
          "The hue picker keeps its meaningful color gradient")
      (named/tab! driver "Customize")
      (s/select-option! driver "#paint-create select[name=scheme]" "Theme Fleet")
      (named/create! driver "Theme Vessel")
      (native-theme! driver "#paint-brush input[type=radio]")
      (is (= "0px" (s/js driver "() => getComputedStyle(document.querySelector('#paint-brush input[type=radio]')).padding"))
          "Paint radios do not inherit text-field padding")
      (is (= "Theme Vessel" (-> (ships/snapshot! ship-db) :ships vals first :ship/name)))
      (named/tab! driver "Assembly")
      (s/fill-and-blur! driver ".assembly__save input[name=name]" "Unsaved name")
      (s/click! driver ".assembly__hull button")
      (s/wait-visible! driver ".assembly-discard")
      (themed! driver ".assembly-discard button")
      (themed! driver ".assembly-discard button[type=button]")
      (s/click! driver ".assembly-discard button:text-is('Cancel')")
      (s/wait-visible! driver ".assembly__save input[name=name]")
      (is (= "Unsaved name" (s/js driver "() => document.querySelector('.assembly__save input[name=name]').value")))
      (s/screenshot-el! driver "#detail" (java.io.File. "/tmp/shipyard-shared-theme.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(defn- mount-layout! [driver]
  (let [layout (s/js driver "() => {
    const f=document.querySelector('.mount-wizard__form'), r=f.getBoundingClientRect();
    const visible=e=>e.checkVisibility();
    const controls=[...f.querySelectorAll('input:not([type=hidden]):not([type=checkbox]),select')].filter(visible);
    const groups=[...f.querySelectorAll('.mount-wizard__faces,.mount-wizard__roles,.mount-wizard__mirror,.mount-wizard__cut')].filter(visible);
    return {fits:f.scrollWidth<=f.clientWidth+1,
      controls:controls.every(e=>{const b=e.getBoundingClientRect();return b.left>=r.left-1&&b.right<=r.right+1&&b.width>=120}),
      groups:groups.length===4&&groups.every(e=>{const b=e.getBoundingClientRect();return Math.abs(b.left-r.left)<1&&Math.abs(b.right-r.right)<1})};}")]
    (is (:fits layout) "The mount form has no horizontal overflow")
    (is (:controls layout) "Every visible field fits and remains wide enough to use")
    (is (:groups layout) "Mount authoring sections share the form's width")))

(deftest mount-controls-fit-the-inspector-and-save-after-resizing
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) part-id (:prow fixture/ids)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "prow")
      (s/await-part driver part-id)
      (s/click! driver "[data-detail-tab=mounts]")
      (is (s/wait-until #(= part-id (get-in (s/stats driver) [:authoring :part-id]))))
      (let [{:keys [x y]} (s/bounds driver "#viewport")
            [width height] (s/js driver "() => {const r=document.querySelector('#viewport').getBoundingClientRect();return [r.width,r.height]}")]
        (s/click-point! driver (+ x (/ width 2)) (+ y (/ height 2))))
      (s/wait-visible! driver ".mount-wizard__form")
      (s/select-option! driver "select[name=kind]" "socket")
      (s/check! driver "input[name=face-edit]")
      (s/check! driver "input[name=create-pitted]")
      (s/check! driver ".mount-wizard__form input[name=mirror]")
      (native-theme! driver "input[name=face-edit]")
      (native-theme! driver "[data-mount-face-radius]")
      (themed! driver "select[name=accepts]")
      (is (= ["0.5" "not-allowed"]
             (s/js driver "() => {const s=getComputedStyle(document.querySelector('[data-mount-faces-undo]'));return [s.opacity,s.cursor]}"))
          "Disabled undo is visibly unavailable")
      (doseq [width [1280 900 600]]
        (.setViewportSize ^Page (:page driver) width 900)
        (mount-layout! driver))
      (s/screenshot-el! driver "#detail" (java.io.File. "/tmp/shipyard-mount-theme-narrow.png"))
      (.setViewportSize ^Page (:page driver) 1280 900)
      (s/select-option! driver "select[name=cut-kind]" "Recess")
      (mount-layout! driver)
      (.uncheck ^Page (:page driver) "input[name=create-pitted]")
      (.uncheck ^Page (:page driver) ".mount-wizard__form input[name=mirror]")
      (s/fill-and-blur! driver ".mount-wizard__form input[name=mount-id]" "theme-socket")
      (s/click! driver ".mount-wizard__actions button[value=create]")
      (is (s/wait-until #(some (fn [m] (= :theme-socket (:mount/id m)))
                               (:part/mounts (:part (catalog/part-context! cat part-id))))))
      (.reload ^Page (:page driver))
      (s/await-part driver part-id)
      (s/click! driver "[data-detail-tab=mounts]")
      (s/wait-visible! driver ".mounts__list")
      (is (some #(= :socket (:mount/kind %))
                (filter #(= :theme-socket (:mount/id %)) (:part/mounts (:part (catalog/part-context! cat part-id))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
