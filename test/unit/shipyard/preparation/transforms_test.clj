(ns shipyard.preparation.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.preparation.transforms :as transforms]))

(deftest trim-test
  (testing "byte and count bounds evict only completed least recently used resources"
    (let [entries {:a {:state :ready :size 8 :access 1}
                   :b {:state :ready :size 8 :access 2}
                   :c {:state :running :access 0}}]
      (is (= #{:b :c} (set (keys (transforms/trim entries 8 10)))))
      (is (= #{:b :c} (set (keys (transforms/trim entries 100 1)))))
      (is (= #{:c} (set (keys (transforms/trim entries 0 0)))))))
  (testing "empty caches stay empty" (is (= {} (transforms/trim {} 0 0)))))

(deftest envelope-test
  (testing "wire status never contains the heavy projection or captured source"
    (is (= {:state :ready :resource "id"}
           (transforms/envelope {:state :ready :resource "id" :value [1 2] :bytes [3] :expected {:root "secret"}})))))
