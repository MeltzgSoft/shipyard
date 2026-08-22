(ns shipyard.unit.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.system :as system]))

(deftest deep-merge-test
  (testing "nested maps merge rather than replace"
    (is (= {:a {:x 1 :y 2}} (system/deep-merge {:a {:x 1}} {:a {:y 2}}))))
  (testing "a later scalar wins"
    (is (= {:a 2} (system/deep-merge {:a 1} {:a 2}))))
  (testing "nil does not erase an existing value"
    (is (= {:a 1} (system/deep-merge {:a 1} nil)))))

(deftest expand-home-test
  (let [home (System/getProperty "user.home")]
    (is (= (str home "/models") (system/expand-home "~/models")))
    (is (= "/abs/path" (system/expand-home "/abs/path")))
    (is (nil? (system/expand-home nil)))))
