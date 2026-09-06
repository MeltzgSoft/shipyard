(ns shipyard.catalog.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [shipyard.catalog.db :as db]))

(deftest authoring-sidecar-test
  (testing "unrelated durable data survives"
    (is (= {:kept true :mounts [{:mount/id :prow}] :part/role :hull}
           (db/authoring-sidecar {:kept true}
                                 {:mounts [{:mount/id :prow}] :part-role :hull}))))
  (testing "an absent role does not erase an existing override"
    (is (= {:part/role :hull :mounts []}
           (db/authoring-sidecar {:part/role :hull}
                                 {:mounts []})))))

(deftest authoring-tx-test
  (let [catalog (d/db-with (d/empty-db db/schema)
                           [{:part/id "hull"
                             :part/mounts [{:mount/id :old :mount/kind :socket}]}])
        tx (db/authoring-tx catalog "hull"
                            {:mounts [{:mount/id :new :mount/kind :socket}]
                             :part-role :hull})]
    (testing "existing component entities are retracted"
      (is (= :db.fn/retractEntity (ffirst tx))))
    (testing "the replacement carries normalized values"
      (is (= {:part/id "hull"
              :part/mounts [{:mount/id :new :mount/kind :socket}]
              :part/role-hint :hull
              :part/role-source :manual}
             (last tx))))))
