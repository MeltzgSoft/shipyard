(ns shipyard.e2e.classification-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright Page]))

(deftest new-values-are-reusable-in-filters-and-editors
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        cat (:shipyard.catalog/db sys) id (:prow fixture/ids)]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "[data-bulk-select]")
      (is (zero? (s/count-els driver ".classification-editor")))
      (is (zero? (s/count-els driver ".part-column-edit .classification-picker small")))
      (doseq [selected [id (:weapon fixture/ids)]]
        (s/check! driver (str "[data-bulk-select][value='" selected "']")))
      (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
      (s/click! driver ".part-column-edit .classification-picker:has(input[name=bundle]) [data-classification-toggle]")
      (s/click! driver "#part-column-bundle-options [role=option]:text-is('Synthetic Navy')")
      (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#part-column-bundle').value")))
      (.fill ^Page (:page driver) "#part-column-bundle" "synthetic navy")
      (is (= 1 (s/count-els driver "#part-column-bundle-options [role=option]")) "An existing value has no duplicate Add choice")
      (.press ^Page (:page driver) "#part-column-bundle" "ArrowDown")
      (.press ^Page (:page driver) "#part-column-bundle" "Enter")
      (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#part-column-bundle').value")))
      (doseq [[field value saved] [["bundle" "Custom Fleet" "Custom Fleet"]
                                   ["class" "Custom Carrier" "Custom Carrier"]
                                   ["role" "Sensor Array" "sensor-array"]]]
        (let [before (catalog/summary! cat id)]
          (.fill ^Page (:page driver) (str "#part-column-" field) value)
          (s/click! driver (str "#part-column-" field "-options [role=option]:text-is('Add “" value "”')"))
          (is (= before (catalog/summary! cat id)) "Choosing a new value waits for Update selected"))
        (s/click! driver "#part-column-update")
        (is (s/wait-until #(= 1 (s/count-els driver (str "#bulk-orient-filters option[value='" saved "']"))))))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='Custom Fleet']")))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='Custom Carrier']")))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='sensor-array']")))
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat id))))
      (s/click! driver ".part-column-edit .classification-picker:has(input[name=role]) [data-classification-toggle]")
      (s/click! driver "#part-column-role-options [role=option]:text-is('sensor-array')")
      (is (= "sensor-array" (s/js driver "() => document.querySelector('#part-column-role').value")))
      (.fill ^Page (:page driver) "#part-column-role" "bad/role")
      (.press ^Page (:page driver) "#part-column-role" "Escape")
      (s/click! driver "#part-column-update")
      (s/wait-visible! driver "#part-edit-status .detail__error")
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat id))))
      (s/select-option! driver "#bulk-orient-filters select[name=role]" "sensor-array")
      (is (s/wait-until #(= 2 (s/count-els driver ".bulk-orient__row"))))
      (.dblclick ^Page (:page driver) (str "[data-part-row='" id "'] .bulk-orient__part"))
      (s/await-part driver id)
      (doseq [[field value] [["bundle" "Custom Fleet"] ["class" "Custom Carrier"] ["role" "sensor-array"]]]
        (is (= 1 (s/count-els driver (str "#part-" field "-values option[value='" value "']")))))
      (s/click! driver ".part-metadata__form input[name=role]")
      (let [option ".part-metadata__form .classification-picker:has(input[name=role]) [role=option]:text-is('sensor-array')"]
        (s/wait-visible! driver option)
        (is (= 1 (s/count-els driver option)))
        (s/click! driver option))
      (is (= "sensor-array" (s/js driver "() => document.querySelector('.part-metadata__form input[name=role]').value")))
      (s/click! driver ".part-metadata__form button:text-is('Save metadata')")
      (s/wait-visible! driver ".part-metadata__form [role=status]:text-is('Saved.')")
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat id))))
      (s/wait-visible! driver "[data-part-back]")
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver "#bulk-orient-filters")
      (s/go! driver (s/base-url sys))
      (is (s/wait-until #(= 1 (s/count-els driver "#part-role-values option[value='sensor-array']"))))
      (is (= 1 (s/count-els driver "#part-bundle-values option[value='Custom Fleet']")))
      (is (= 1 (s/count-els driver "#part-class-values option[value='Custom Carrier']")))
      (s/check! driver "#part-select-matching")
      (s/wait-visible! driver "#part-column-update")
      (s/click! driver ".part-column-edit .classification-picker:has(input[name=role]) [data-classification-toggle]")
      (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-classification-selector.png"))
      (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-classifications.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))
