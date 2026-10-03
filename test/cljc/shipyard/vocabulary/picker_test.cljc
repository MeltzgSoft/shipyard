(ns shipyard.vocabulary.picker-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.vocabulary.picker :as picker]))

(deftest options-test
  (testing "existing values are sorted, deduplicated and searchable"
    (is (= [{:value "Carrier" :new? false} {:value "Cruiser" :new? false}]
           (picker/options ["Cruiser" "Carrier" "Cruiser"] "")))
    (is (= [{:value "Cruiser" :new? false} {:value "RUI" :new? true}]
           (picker/options ["Cruiser" "Carrier"] " RUI "))))
  (testing "a complete existing value never gets a duplicate Add option"
    (is (= [{:value "Carrier" :new? false}] (picker/options ["Carrier"] "carrier"))))
  (testing "empty choices support creation but empty input does not"
    (is (= [{:value "Sensor Array" :new? true}] (picker/options [] "Sensor Array")))
    (is (empty? (picker/options [] "  ")))))
