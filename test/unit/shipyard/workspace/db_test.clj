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
  (is (not (transforms/current-request? 2 3 :ships :ships)))
  (is (transforms/current-request? 3 3 :ships :ships))
  (is (not (transforms/current-request? 3 2 :ships :ships)))
  (is (not (transforms/current-request? 3 3 :ships :assembly))))

(deftest drawer-states-test
  (let [parent [[:weapon 0]] child [[:weapon 0] [:turret 0]]
        incomplete [{:id parent :assigned "weapon"} {:id child :assigned nil}]
        complete (assoc-in incomplete [1 :assigned] "turret")]
    (is (= {parent {:complete false :open true} child {:complete false :open true}}
           (transforms/drawer-states {} incomplete)))
    (is (false? (get-in (transforms/drawer-states {parent {:complete false :open false}} incomplete) [parent :open])))
    (is (= {parent {:complete true :open false} child {:complete true :open false}}
           (transforms/drawer-states {} complete)))
    (is (true? (get-in (transforms/drawer-states {parent {:complete true :open false}} incomplete) [parent :open])))
    (is (= {} (transforms/drawer-states {parent {:complete true :open false}} [])))))
