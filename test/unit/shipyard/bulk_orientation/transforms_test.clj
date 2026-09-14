(ns shipyard.bulk-orientation.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.part.orientation :as orientation]))

(deftest saved?-test
  (is (false? (bulk/saved? {})))
  (is (true? (bulk/saved? {:part/orientation orientation/identity-quaternion}))))

(deftest matches-orientation?-test
  (let [unset {}
        saved {:part/orientation orientation/identity-quaternion}]
    (is (bulk/matches-orientation? nil unset))
    (is (bulk/matches-orientation? "all" saved))
    (is (bulk/matches-orientation? "unset" unset))
    (is (not (bulk/matches-orientation? "unset" saved)))
    (is (bulk/matches-orientation? "saved" saved))
    (is (not (bulk/matches-orientation? "saved" unset)))
    (is (not (bulk/matches-orientation? "unexpected" saved)))))

(deftest selected-ids-test
  (testing "normalizes repeated ids while preserving order"
    (is (= ["a" "b"] (bulk/selected-ids "[\"a\" \"b\" \"a\"]"))))
  (testing "rejects malformed or non-string selections"
    (is (nil? (bulk/selected-ids "{:a 1}")))
    (is (nil? (bulk/selected-ids "[1]")))
    (is (nil? (bulk/selected-ids "not edn")))))

(deftest orientations-request-test
  (let [q orientation/identity-quaternion]
    (is (= {"a" q} (bulk/orientations-request {"orientations" (pr-str {"a" q})})))
    (is (nil? (bulk/orientations-request {"orientations" "{}"})))
    (is (nil? (bulk/orientations-request {"orientations" "{\"a\" [0 0 0 0]}"})))
    (is (nil? (bulk/orientations-request {"orientations" "broken"})))))
