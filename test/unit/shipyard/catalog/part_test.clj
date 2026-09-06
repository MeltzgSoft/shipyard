(ns shipyard.catalog.part-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.catalog.part :as part]))

(deftest durable-mounts-test
  (testing "removes database-only identifiers"
    (is (= [{:mount/id :weapon-1}]
           (part/durable-mounts [{:db/id 42 :mount/id :weapon-1}]))))
  (testing "supports an empty collection"
    (is (= [] (part/durable-mounts [])))))
