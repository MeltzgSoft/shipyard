(ns shipyard.e2e.classification-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright Page]))

(deftest new-values-are-reusable-in-filters-and-editors
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        cat (:shipyard.catalog/db sys) id (:prow fixture/ids)]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "[data-bulk-select]")
      (is (zero? (s/count-els driver ".classification-editor")))
      (s/check! driver (str "[data-bulk-select][value='" id "']"))
      (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
      (s/click! driver "[data-classification-toggle]")
      (s/click! driver "#part-edit-options [role=option]:text-is('Synthetic Navy')")
      (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#part-edit-value').value")))
      (.fill ^Page (:page driver) "#part-edit-value" "synthetic navy")
      (is (= 1 (s/count-els driver "#part-edit-options [role=option]")) "An existing value has no duplicate Add choice")
      (.press ^Page (:page driver) "#part-edit-value" "ArrowDown")
      (.press ^Page (:page driver) "#part-edit-value" "Enter")
      (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#part-edit-value').value")))
      (doseq [[label value saved] [["Bundle / faction" "Custom Fleet" "Custom Fleet"]
                                   ["Class" "Custom Carrier" "Custom Carrier"]
                                   ["Role" "Sensor Array" "sensor-array"]]]
        (s/select-option! driver ".part-bulk-edit select[name=field]" label)
        (let [before (catalog/summary! cat id)]
          (.fill ^Page (:page driver) "#part-edit-value" value)
          (s/click! driver (str "#part-edit-options [role=option]:text-is('Add “" value "”')"))
          (is (= before (catalog/summary! cat id)) "Choosing a new value waits for Apply to selected"))
        (s/click! driver "#part-bulk-apply")
        (is (s/wait-until #(= 1 (s/count-els driver (str "#bulk-orient-filters option[value='" saved "']"))))))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='Custom Fleet']")))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='Custom Carrier']")))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='sensor-array']")))
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat id))))
      (s/click! driver "[data-classification-toggle]")
      (s/click! driver "#part-edit-options [role=option]:text-is('sensor-array')")
      (is (= "sensor-array" (s/js driver "() => document.querySelector('#part-edit-value').value")))
      (.fill ^Page (:page driver) "#part-edit-value" "bad/role")
      (.press ^Page (:page driver) "#part-edit-value" "Escape")
      (s/click! driver "#part-bulk-apply")
      (s/wait-visible! driver "#part-edit-status .detail__error")
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat id))))
      (s/select-option! driver "#bulk-orient-filters select[name=role]" "sensor-array")
      (is (s/wait-until #(= 1 (s/count-els driver ".bulk-orient__row"))))
      (.dblclick ^Page (:page driver) ".bulk-orient__part")
      (s/await-part driver id)
      (is (= 1 (s/count-els driver ".part-metadata select option[value='sensor-array']")))
      (s/select-option! driver ".part-metadata select" "sensor-array")
      (s/click! driver ".part-metadata button")
      (s/wait-visible! driver "[data-part-back]")
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver "#bulk-orient-filters")
      (s/go! driver (s/base-url sys))
      (is (s/wait-until #(= 1 (s/count-els driver "#part-role-values option[value='sensor-array']"))))
      (is (= 1 (s/count-els driver "#part-bundle-values option[value='Custom Fleet']")))
      (is (= 1 (s/count-els driver "#part-class-values option[value='Custom Carrier']")))
      (s/click! driver "[data-classification-toggle]")
      (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-classification-selector.png"))
      (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-classifications.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))
