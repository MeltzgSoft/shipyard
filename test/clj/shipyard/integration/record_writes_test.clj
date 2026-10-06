(ns shipyard.integration.record-writes-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.store.db :as store]
            [shipyard.store.transforms :as projection]))

(deftest targeted-record-writes-preserve-conflicts-tombstones-and-owned-data
  (let [started (fixture/start!) sys (:system started)
        cat (:shipyard.catalog/db sys) database (:store cat) library (:library @(:state cat))
        class {:loadout/id (random-uuid) :loadout/name "Same name" :loadout/hull (:hull fixture/ids) :loadout/slots {}}
        scheme {:scheme/id (random-uuid) :scheme/name "Same name" :scheme/layers {}}
        ship {:ship/id (random-uuid) :ship/name "Same name" :ship/class (:loadout/id class) :ship/paint {}}
        records [[:loadouts :loadout/id :loadout/name :loadout/deleted? class :missing-loadout]
                 [:schemes :scheme/id :scheme/name :scheme/deleted? scheme :missing-scheme]
                 [:ships :ship/id :ship/name :ship/deleted? ship :missing-ship]]
        project projection/ship-value projected (atom [])]
    (try
      (store/put-record! database library :loadouts class :create)
      (testing "concurrent creation has one winner; names never select update targets"
        (doseq [[kind key name-key _ record _] records]
          (let [record (assoc record key (random-uuid))
                results (mapv deref (mapv (fn [_] (future (store/put-record! database library kind record :create))) (range 6)))]
            (is (= 1 (count (remove :error results))))
            (is (= 5 (count (filter #(= :id-exists (:error %)) results))))
            (is (nil? (:error (store/put-record! database library kind (assoc record name-key "Renamed") :update))))
            (is (= "Same name" (get record name-key))))))
      (testing "writes and deletes never project unrelated ship paint or enumerate a store"
        (with-redefs [projection/ship-value (fn [entity] (swap! projected conj (:ship/id entity)) (project entity))]
          (doseq [[kind key _ deleted record missing] records]
            (when-not (= kind :loadouts) (store/put-record! database library kind record :create))
            (is (nil? (:error (store/put-record! database library kind record :update))))
            (is (nil? (:error (store/delete-record! database kind (get record key)))))
            (is (= missing (:error (store/delete-record! database kind (get record key)))))
            (is (= missing (:error (store/put-record! database library kind record :update))))
            (is (true? (store/read! database #(get (d/pull % [deleted] [key (get record key)]) deleted))))
            (is (nil? (:error (store/put-record! database library kind record :create))) "A soft-deleted identity can be explicitly recreated")))
        (is (empty? @projected)))
      (testing "reopen retains only the intended records and their fields"
        (doseq [[kind key _ _ record _] records]
          (is (= record (get-in (persisted/records! database kind) [kind (get record key)])))))
      (testing "malformed identities retain domain validation results"
        (is (= :invalid-loadout (:error (store/put-record! database library :loadouts (assoc class :loadout/id "bad") :create))))
        (is (= :invalid-loadout-id (:error (store/delete-record! database :loadouts "bad"))))
        (is (= :missing-scheme (:error (store/delete-record! database :schemes nil))))
        (is (= :missing-ship (:error (store/delete-record! database :ships "bad")))))
      (finally (fixture/stop! started)))))
