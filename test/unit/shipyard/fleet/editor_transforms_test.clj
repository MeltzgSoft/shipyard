(ns shipyard.fleet.editor-transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fleet.editor-transforms :as t]))

(deftest quantity-test
  (testing "only positive whole values that fit the durable quantity are accepted"
    (is (= 2 (t/quantity "2")))
    (doseq [value [nil "" "0" "-1" "1.5" "NaN" "999999999999999999999999"]]
      (is (nil? (t/quantity value))))))

(deftest move-variant-test
  (testing "one step without wraparound or inferred variants"
    (let [order [:unsupported :supported :unsupported-pitted]]
      (is (= [:supported :unsupported :unsupported-pitted] (t/move-variant order :supported :up)))
      (is (= [:unsupported :unsupported-pitted :supported] (t/move-variant order :supported :down)))
      (is (= order (t/move-variant order :unsupported :up)))
      (is (= order (t/move-variant order :unsupported-pitted :down)))
      (is (= order (t/move-variant order :unknown :down))))))

(deftest change-test
  (testing "targeted updates preserve every other fleet field"
    (let [class (random-uuid) fleet {:fleet/name "A" :fleet/entries {} :fleet/extras {"part" 2}}]
      (is (= (assoc fleet :fleet/name "B") (:fleet (t/change fleet :rename {"name" " B "}))))
      (is (= (assoc-in fleet [:fleet/entries class] 3)
             (:fleet (t/change fleet :class-set {"class-id" (str class) "quantity" "3"}))))
      (is (= {} (get-in (t/change fleet :extra-remove {"part-id" "part"}) [:fleet :fleet/extras])))
      (is (:error (t/change fleet :extra-set {"part-id" "part" "quantity" "0"})))
      (is (:error (t/change fleet :rename {"name" " "})))
      (is (:error (t/change fleet :class-set {"class-id" "broken" "quantity" "1"}))))))

(deftest selected-test
  (testing "stale forms cannot identify another selected fleet"
    (let [id (random-uuid)]
      (is (t/selected? id {"id" (str id)}))
      (is (not (t/selected? id {"id" (str (random-uuid))})))
      (is (not (t/selected? nil {"id" "bad"}))))))
