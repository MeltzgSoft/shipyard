(ns shipyard.catalog.part-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.catalog.part :as part]))

(deftest durable-mounts-test
  (testing "removes database-only identifiers"
    (is (= [{:mount/id :weapon-1}]
           (part/durable-mounts [{:db/id 42 :mount/id :weapon-1}]))))
  (testing "supports an empty collection"
    (is (= [] (part/durable-mounts [])))))

(deftest summaries-count-socket-capacity-and-plugs
  (is (= {:plugs 1 :sockets {#{:weapon} 5 #{:prow :hull-section} 1}}
         (part/mount-summary [{:mount/kind :plug}
                              {:mount/kind :socket :mount/accepts #{:weapon} :mount/capacity 2}
                              {:mount/kind :socket :mount/accepts #{:weapon} :mount/capacity 3}
                              {:mount/kind :socket :mount/accepts #{:prow :hull-section}}]))))
