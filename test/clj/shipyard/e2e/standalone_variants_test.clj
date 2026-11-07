(ns shipyard.e2e.standalone-variants-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.orient-table-test :as table]
            [shipyard.standalone-variants-fixture :as parts]
            [shipyard.variant-availability-fixture :as variants]))

(defn- row-ids [driver]
  (set (s/js driver "() => [...document.querySelectorAll('[data-part-row]')].map(row=>row.dataset.partRow)")))

(deftest standalone-supported-and-pitted-groups-are-visible-without-extra-filtering
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_])) driver (s/make-driver)
        expected #{variants/all variants/plain variants/cut variants/supported parts/pitted parts/supported-pitted}]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver (table/row variants/supported))
      (is (= expected (row-ids driver)))
      (is (= 6 (s/count-els driver "[data-part-row]")))
      (s/click! driver (str (table/row variants/all) " > summary"))
      (s/wait-visible! driver (str (table/row variants/all) " .import-files"))
      (s/click! driver (str (table/row variants/all) " .import-files > summary"))
      (is (= 3 (s/count-els driver (str (table/row variants/all) " .import-files__file"))))
      (is (= 6 (s/count-els driver "[data-part-row]")))
      (s/select-option! driver "#bulk-orient-filters select[name=variant]" "Supported")
      (is (s/wait-until #(= #{variants/all variants/supported parts/supported-pitted} (row-ids driver))))
      (s/select-option! driver "#bulk-orient-filters select[name=variant]" "Unsupported (pitted / recessed)")
      (is (s/wait-until #(= #{variants/all variants/cut parts/pitted parts/supported-pitted} (row-ids driver))))
      (s/select-option! driver "#bulk-orient-filters select[name=variant]" "All variants")
      (is (s/wait-until #(= expected (row-ids driver))))
      (s/select-option! driver "#bulk-orient-filters select[name=has-unsupported]" "Missing")
      (is (s/wait-until #(= #{variants/supported parts/pitted parts/supported-pitted} (row-ids driver))))
      (s/click! driver "[data-filter-clear]")
      (is (s/wait-until #(= expected (row-ids driver))))
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#settings-workspace")
      (s/click! driver "[data-workspace-mode=browse]")
      (s/wait-visible! driver (table/row variants/supported))
      (is (= expected (row-ids driver)))
      (finally (s/quit! driver) (fixture/stop! started)))))
