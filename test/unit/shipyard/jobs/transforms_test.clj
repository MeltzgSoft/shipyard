(ns shipyard.jobs.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.jobs.transforms :as t]))

(deftest limits-test
  (testing "bounded defaults and reserves for small configured backlogs"
    (is (= {:threads 2 :queue-size 4096 :interactive-reserve 32} (t/limits {})))
    (is (= (t/limits {}) (t/limits {:threads nil :queue-size nil})))
    (is (= {:threads 3 :queue-size 8 :interactive-reserve 2} (t/limits {:threads 3 :queue-size 8})))
    (is (= 0 (:interactive-reserve (t/limits {:queue-size 1}))))
    (is (= 0 (:interactive-reserve (t/limits {:interactive-reserve 0})))))
  (testing "reject invalid budgets"
    (doseq [field [:threads :queue-size] value [false 0 -1 :auto "2" 2.5 2147483648]]
      (is (thrown? clojure.lang.ExceptionInfo (t/limits {field value}))))
    (doseq [value [-1 false 2.5 4096]]
      (is (thrown? clojure.lang.ExceptionInfo (t/limits {:interactive-reserve value}))))))

(deftest admits?-test
  (let [limits {:queue-size 10 :interactive-reserve 2}]
    (testing "bulk cannot consume interactive capacity; rejected batches are indivisible"
      (is (t/admits? limits :bulk 0 0 8))
      (is (not (t/admits? limits :bulk 0 0 9)))
      (is (t/admits? limits :interactive 8 8 2))
      (is (not (t/admits? limits :interactive 8 8 3)))
      (is (not (t/admits? limits :bulk 10 0 1))))))

(deftest next-priority-test
  (testing "three interactive dispatches guarantee one bulk turn without starving either queue"
    (is (nil? (t/next-priority false false 0)))
    (is (= :bulk (t/next-priority false true 0)))
    (is (= :interactive (t/next-priority true false 100)))
    (is (= :interactive (t/next-priority true true 0)))
    (is (= :interactive (t/next-priority true true 2)))
    (is (= :bulk (t/next-priority true true 3)))))

(deftest unique-descriptors-test
  (testing "first immutable-key claim wins and FIFO order is retained"
    (is (= [] (t/unique-descriptors [])))
    (is (= [{:key :b :args [1]} {:key :a :args [2]}]
           (t/unique-descriptors [{:key :b :args [1]} {:key :a :args [2]} {:key :b :args [3]}])))))
