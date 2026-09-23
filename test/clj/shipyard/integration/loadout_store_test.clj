(ns shipyard.integration.loadout-store-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout.db :as db]
            [shipyard.persistence-fixture :as persisted]))

(deftest durable-identities-concurrency-and-deletion
  (let [started (fixture/start!) facade (:shipyard.loadout/db (:system started))
        record {:loadout/id (random-uuid) :loadout/name "Ship" :loadout/hull (:hull fixture/ids)
                :loadout/slots {[[:weapon 0]] (:weapon fixture/ids)} :loadout/scheme (random-uuid)}]
    (try
      (is (= record (:loadout (db/put! facade record :create))))
      (is (= record (get-in (persisted/records! facade :loadouts) [:loadouts (:loadout/id record)])))
      (is (= :id-exists (:error (db/put! facade record :create))))
      (is (= :missing-loadout (:error (db/put! facade (assoc record :loadout/id (random-uuid)) :update))))
      (let [copies (vec (repeatedly 8 #(assoc record :loadout/id (random-uuid))))]
        (is (every? :loadout (mapv deref (mapv #(future (db/put! facade % :create)) copies))))
        (is (= 9 (count (:loadouts (persisted/records! facade :loadouts))))))
      (is (= {:deleted (:loadout/id record)} (db/delete! facade (:loadout/id record))))
      (is (= :missing-loadout (:error (db/delete! facade (:loadout/id record)))))
      (is (nil? (get-in (persisted/records! facade :loadouts) [:loadouts (:loadout/id record)])))
      (finally (fixture/stop! started)))))
