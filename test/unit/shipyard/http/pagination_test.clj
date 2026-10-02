(ns shipyard.http.pagination-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.http.pagination :as pagination]))

(deftest batch-window
  (doseq [page [nil "garbage" "-4" "0"]]
    (is (= 50 (count (:items (pagination/batch-window (range 120) page false))))))
  (is (= (vec (range 50 100)) (:items (pagination/batch-window (range 120) "2" true))))
  (is (= (vec (range 100)) (:items (pagination/batch-window (range 120) "2" false))))
  (is (empty? (:items (pagination/batch-window (range 120) "9223372036854775807" true))))
  (is (= 120 (:loaded (pagination/batch-window (range 120) "999" false))))
  (is (false? (:more? (pagination/batch-window [] "3" false)))))

(deftest same-filters
  (is (not (pagination/same-filters? {"variant" "supported"} {"variant" "unsupported"})))
  (is (pagination/same-filters? {"q" "Hull" "page" "2"} {"q" "Hull" "bundle" ""}))
  (is (not (pagination/same-filters? {"q" "Hull"} {"q" "Prow"}))))
