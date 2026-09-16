(ns shipyard.e2e.orient-table-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.e2e.support :as s]))

(defn row [id] (str ".bulk-orient__row:has(input[value='" id "'])"))
(defn filter! [driver label]
  (s/select-option! driver "#bulk-orient-filters select[name=orientation]" label))

(deftest save-back-and-filter-refresh-metadata-without-losing-selection
  (let [started (fixture/start! true) driver (s/make-driver)
        a (:prow fixture/ids) b (:bridge fixture/ids)
        file (sidecar/sidecar-file (str (:root started)) b) backup (str file ".backup")]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/click! driver ".masthead [data-workspace-mode='orient']")
      (s/wait-visible! driver "[data-bulk-select]")
      (filter! driver "Orientation unset")
      (doseq [id [a b]] (s/check! driver (str "[data-bulk-select][value='" id "']")))
      (s/click! driver "[data-bulk-render-button]")
      (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count]))))
      (fs/move file backup)
      (spit file "{")
      (orient/set-yaw! driver 45)
      (orient/save! driver)
      (is (s/wait-until #(str/includes? (s/text driver "#bulk-orient-status") "Saved 1. Failed:")))
      (fs/delete file)
      (fs/move backup file)
      (s/click! driver "[data-bulk-back]")
      (is (s/wait-until #(zero? (s/count-els driver (row a)))))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (is (str/includes? (s/text driver (row b)) "Unset"))
      (filter! driver "Orientation saved")
      (s/wait-visible! driver (row a))
      (is (= 1 (s/count-els driver ".bulk-orient__row")))
      (is (str/includes? (s/text driver (row a)) "45"))
      (is (str/includes? (s/text driver (row a)) "Saved"))
      (filter! driver "Any orientation")
      (s/wait-visible! driver (row b))
      (is (str/includes? (s/text driver (row b)) "Unset"))
      (is (str/includes? (s/text driver (row a)) "Saved"))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (s/click! driver "[data-bulk-render-button]")
      (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count]))))
      (orient/set-yaw! driver 0)
      (orient/save! driver)
      (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
      (s/click! driver "[data-bulk-back]")
      (is (s/wait-until #(str/includes? (s/text driver (row b)) "Saved")))
      (filter! driver "Orientation saved")
      (is (s/wait-until #(= 2 (s/count-els driver ".bulk-orient__row"))))
      (doseq [id [a b]]
        (is (= [0.0 0.0 0.0 1.0] (:part/orientation (sidecar/read-sidecar! (str (:root started)) id)))))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (finally (s/quit! driver) (fixture/stop! started)))))
