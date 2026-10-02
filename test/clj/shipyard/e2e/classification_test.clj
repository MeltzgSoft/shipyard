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
      (doseq [[label value] [["Bundle / faction" "Custom Fleet"] ["Class" "Custom Carrier"] ["Role" "Sensor Array"]]]
        (s/select-option! driver ".part-bulk-edit select[name=field]" label)
        (s/fill-and-blur! driver ".part-bulk-edit input[name=value]" value)
        (s/click! driver ".part-bulk-edit button")
        (is (s/wait-until #(not (s/js driver "() => document.querySelector('.part-bulk-edit button').disabled")))))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='Custom Fleet']")))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='Custom Carrier']")))
      (is (= 1 (s/count-els driver "#bulk-orient-filters option[value='sensor-array']")))
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
      (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-classifications.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))
