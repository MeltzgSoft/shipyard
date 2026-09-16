(ns shipyard.workspace.db-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.workspace.db :as w]
            [shipyard.workspace.transforms :as transforms]))

(deftest owner-test
  (is (= :orient (w/owner "/orient/save")))
  (is (= :assembly (w/owner "/assembly/save")))
  (is (= :ships (w/owner "/ships/preview")))
  (is (= :browse (w/owner "/part/navy/hull")))
  (is (nil? (w/owner "/workspace/ships"))))

(deftest selected-filters-test
  (is (= [:form [:select {:name "bundle"}
                 [[:option {:value "a" :selected false} "A"]
                  [:option {:value "b" :selected true} "B"]]]]
         (transforms/selected-filters
          [:form [:select {:name "bundle"} [[:option {:value "a"} "A"] [:option {:value "b"} "B"]]]]
          {"bundle" "b"}))))

(deftest request-ordering-test
  (is (transforms/current-request? 2 3 :ships :ships))
  (is (transforms/current-request? 3 3 :ships :ships))
  (is (not (transforms/current-request? 3 2 :ships :ships)))
  (is (not (transforms/current-request? 3 3 :ships :assembly))))
