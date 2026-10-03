(ns shipyard.e2e.settings-workspace-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.settings.db :as settings])
  (:import [com.microsoft.playwright Page]))

(deftest settings-values-defaults-and-return-to-the-selected-part
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver) cat (:shipyard.catalog/db sys) database (:shipyard.store/db sys)
        id (:prow fixture/ids)]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "#bulk-orient-filters")
      (is (zero? (s/count-els driver "#settings")) "Folder controls moved out of the browser")
      (s/open-part! driver "prow")
      (s/await-part driver id)
      (let [pose (:quaternion (s/stats driver))]
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#settings-workspace")
        (is (s/js driver "() => getComputedStyle(document.querySelector('.stage')).display==='none'"))
        (let [row "[data-classification-field=bundle][data-classification-value='Synthetic Navy']"]
          (is (s/js driver "() => document.querySelector('[data-classification-value=\"Synthetic Navy\"] form[hx-post$=delete] button').disabled"))
          (.fill page (str row " input[name=new-value]") "Renamed Navy")
          (s/click! driver (str row " form[hx-post$=rename] button"))
          (s/wait-visible! driver "[data-classification-value='Renamed Navy']")
          (is (= "Renamed Navy" (:part/bundle (catalog/summary! cat id)))))
        (.fill page ".settings-workspace__section:has(h3:text-is('Class')) .settings-workspace__add input[name=value]" "Disposable")
        (s/click! driver ".settings-workspace__section:has(h3:text-is('Class')) .settings-workspace__add button")
        (s/wait-visible! driver "[data-classification-value=Disposable]")
        (s/click! driver "[data-classification-value=Disposable] form[hx-post$=delete] button")
        (is (s/wait-until #(zero? (s/count-els driver "[data-classification-value=Disposable]"))))
        (is (s/js driver "() => document.querySelector('[data-classification-field=role][data-classification-value=weapon]').textContent.includes('Built-in')"))
        (doseq [[field value] [["pit-depth" "0.6"] ["pit-diameter" "0.8"] ["recess-depth" "0.4"] ["recess-border" "0.1"]]]
          (.fill page (str "#cut-defaults [name=" field "]") value))
        (s/click! driver "[data-workspace-mode=browse]")
        (s/await-part driver id)
        (is (= pose (:quaternion (s/stats driver))) "The browser keeps its viewport pose")
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#cut-defaults")
        (is (= "0.6" (s/js driver "() => document.querySelector('[name=pit-depth]').value")) "Unsaved defaults survive a workspace round trip")
        (s/click! driver "#cut-defaults button")
        (s/wait-visible! driver "[role=status]:text-is('Saved mount-cut defaults.')")
        (is (= {:pit {:depth 0.6 :diameter 0.8} :recess {:depth 0.4 :border 0.1}} (settings/cut-defaults! database)))
        (s/screenshot-el! driver "#settings-workspace" (java.io.File. "/tmp/shipyard-settings-workspace.png"))
        (s/click! driver "[data-workspace-mode=browse]")
        (s/await-part driver id)
        (s/click! driver "[data-detail-tab=mounts]")
        (is (s/wait-until #(= id (get-in (s/stats driver) [:authoring :part-id]))))
        (let [{:keys [x y]} (s/js driver "() => {const r=document.getElementById('viewport').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}}")]
          (s/click-point! driver x y))
        (s/wait-visible! driver ".mount-wizard__form")
        (s/check! driver "input[name=create-pitted]")
        (s/select-option! driver "select[name=cut-kind]" "Pit")
        (is (= "0.6" (s/js driver "() => document.querySelector('[name=cut-depth]').value")))
        (is (= "0.8" (s/js driver "() => document.querySelector('[name=cut-diameter]').value")))
        (s/select-option! driver "select[name=cut-kind]" "Recess")
        (is (= "0.4" (s/js driver "() => document.querySelector('[name=cut-depth]').value")))
        (is (= "0.1" (s/js driver "() => document.querySelector('[name=cut-border]').value")))
        (.fill page "input[name=cut-depth]" "0.25")
        (s/select-option! driver "select[name=cut-kind]" "Pit")
        (is (= "0.25" (s/js driver "() => document.querySelector('[name=cut-depth]').value")) "User-entered dimensions are preserved"))
      (finally (s/quit! driver) (fixture/stop! started)))))
