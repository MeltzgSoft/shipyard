(ns shipyard.e2e.orient-table-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright APIResponse Page Route Route$FulfillOptions]
           [java.util.function Consumer]))

(defn row [id] (str ".bulk-orient__row:has(input[value='" id "'])"))
(defn filter! [driver label]
  (s/select-option! driver "#bulk-orient-filters select[name=orientation]" label))

(deftest save-back-and-filter-refresh-metadata-without-losing-selection
  (let [started (fixture/start! true) driver (s/make-driver)
        a (:prow fixture/ids) b (:bridge fixture/ids)
        cat (:shipyard.catalog/db (:system started))]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/click! driver ".masthead [data-workspace-mode='browse']")
      (s/wait-visible! driver "[data-bulk-select]")
      (filter! driver "Orientation unset")
      (doseq [id [a b]] (s/check! driver (str "[data-bulk-select][value='" id "']")))
      (s/click! driver "[data-bulk-render-button]")
      (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count]))))
      (persisted/available! cat b false)

      (orient/set-yaw! driver 45)
      (orient/save! driver)
      (is (s/wait-until #(str/includes? (s/text driver "#bulk-orient-status") "Saved 1. Failed:")))

      (persisted/available! cat b true)
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
        (is (= [0.0 0.0 0.0 1.0] (:part/orientation (persisted/authored! (:shipyard.catalog/db (:system started)) id)))))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest navigation-cannot-discard-an-orientation-filter-change
  (let [started (fixture/start! true) driver (s/make-driver)
        ^Page page (:page driver) held (atom nil)
        value #(s/js driver "() => document.querySelector('#bulk-orient-filters select[name=orientation]').value")]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "[data-bulk-select]")
      (.route page "**/workspace/browse*"
              (reify Consumer
                (accept [_ route]
                  (reset! held [route (.fetch ^Route route)]))))
      (let [activation (s/js driver "() => document.querySelector('#workspace-context').dataset.activation")]
        (s/click! driver "[data-workspace-mode=browse]")
        (is (s/wait-until #(do (s/js driver "() => document.readyState") (some? @held))))
        (is (s/js driver "() => document.querySelector('#bulk-orient-filters select[name=orientation]').disabled")
            "the outgoing table cannot accept a filter that navigation would overwrite")
        (let [[^Route route ^APIResponse response] @held]
          (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
        (is (s/wait-until #(not= activation (s/js driver "() => document.querySelector('#workspace-context').dataset.activation"))))
        (filter! driver "Orientation unset")
        (is (= "unset" (value)))
        (.unroute page "**/workspace/browse*")
        (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
        (s/click! driver "[data-bulk-render-button]")
        (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
        (orient/set-yaw! driver 45)
        (orient/save! driver)
        (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
        (s/click! driver "[data-bulk-back]")
        (s/wait-visible! driver "#bulk-orient-filters")
        (is (= "unset" (value)))
        (is (s/wait-until #(zero? (s/count-els driver (row (:prow fixture/ids)))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
