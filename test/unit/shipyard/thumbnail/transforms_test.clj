(ns shipyard.thumbnail.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.thumbnail.transforms :as t]))

(deftest cache-key-is-stable-and-content-sensitive
  (is (= (t/cache-key (array-map :mesh "a" :pose {:x 1 :y 2} :members #{:a :b}))
         (t/cache-key (array-map :members #{:b :a} :pose {:y 2 :x 1} :mesh "a"))))
  (is (re-matches #"[0-9a-f]{64}" (t/cache-key {})))
  (is (= (t/cache-key [1 2 3]) (binding [*print-length* 1] (t/cache-key [1 2 3]))))
  (doseq [changed [{:mesh "b"} {:mesh "a" :pose [1 0 0]} {:mesh "a" :paint {:base [1 0 0]}}]]
    (is (not= (t/cache-key {:mesh "a"}) (t/cache-key changed))))
  (is (not= (t/cache-key [1 2]) (t/cache-key [2 1]))))
