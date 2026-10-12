(ns shipyard.integration.fleet-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fleet.db :as fleet]
            [shipyard.loadout.db :as loadout]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.store.db :as store]))

(deftest durable-fleet-lists-and-transaction-rejection
  (let [started (fixture/start!) sys (:system started)
        database (:shipyard.store/db sys) catalog (:shipyard.catalog/db sys)
        facade {:store database :catalog catalog}
        library (:library @(:state catalog))
        class {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull fixture/ids)
               :loadout/slots {[[:weapon 0]] (:weapon fixture/ids)}}]
    (try
      (loadout/put! (:shipyard.loadout/db sys) class :create)
      (let [first (:fleet (fleet/create! facade "First"))
            second (:fleet (fleet/create! facade "Second"))
            id (:fleet/id first)
            populated (assoc first :fleet/entries {(:loadout/id class) 2}
                             :fleet/extras {(:weapon fixture/ids) 3})]
        (testing "independent identities and durable references survive reopening"
          (is (= populated (:fleet (fleet/put! facade populated :update))))
          (is (= #{id (:fleet/id second)} (set (keys (:fleets (fleet/snapshot! facade))))))
          (is (= populated (get-in (persisted/records! facade :fleets) [:fleets id])))
          (is (= (:loadout/id class) (get-in (fleet/export-snapshot! facade id)
                                             [:classes (:loadout/id class) :loadout/id])))
          (is (= (str (:hull fixture/ids) "/unsupported.stl")
                 (get-in (fleet/export-snapshot! facade id)
                         [:parts (:hull fixture/ids) :part/sources :unsupported :source/path]))))
        (testing "invalid direct writes preserve both persisted and published lists"
          (doseq [bad [(assoc populated :fleet/entries {(:loadout/id class) 0})
                       (assoc populated :fleet/extras {(:weapon fixture/ids) 1.5})
                       (assoc populated :fleet/variant-preference [:supported :supported])
                       (assoc populated :fleet/variant-preference [:invented])
                       (assoc populated :fleet/library (random-uuid))
                       (assoc populated :fleet/entries {(random-uuid) 1})
                       (assoc populated :fleet/extras {"Unknown/part" 1})]]
            (is (:error (fleet/put! facade bad :update)))
            (is (= populated (fleet/record! facade id)))
            (is (= populated (get-in (persisted/records! facade :fleets) [:fleets id])))))
        (testing "default copy is transactional and existing list preferences stay independent"
          (store/write! database #(d/transact! % [{:store/key "shipyard" :settings/variant-preference [:supported]}]))
          (let [third (:fleet (fleet/create! facade "Third"))]
            (is (= [:supported] (:fleet/variant-preference third)))
            (is (= (:fleet/variant-preference first) (:fleet/variant-preference (fleet/record! facade id))))
            (is (= [:supported] (get-in (fleet/use-default! facade id) [:fleet :fleet/variant-preference])))))
        (testing "cross-library classes are rejected and library switching isolates lists"
          (let [other (random-uuid) other-class (random-uuid)]
            (store/write! database
                          #(d/transact! % [{:library/id other :library/root (str (:root started) "-other")}
                                           {:loadout/id other-class :loadout/name "Other" :loadout/library [:library/id other]}]))
            (is (:error (fleet/put! facade (assoc (fleet/record! facade id) :fleet/entries {other-class 1}) :update)))
            (swap! (:state catalog) assoc :library other)
            (is (empty? (:fleets (fleet/snapshot! facade))))
            (is (= :missing-fleet (:error (fleet/delete! facade id))))
            (is (get-in (persisted/records! facade :fleets) [:fleets id]))
            (is (nil? (fleet/export-snapshot! facade id)))
            (swap! (:state catalog) assoc :library library)
            (is (fleet/record! facade id))))
        (testing "exports require an actual selected fleet and library"
          (is (nil? (fleet/export-snapshot! facade nil)))
          (is (nil? (fleet/export-snapshot! facade (random-uuid))))
          (swap! (:state catalog) assoc :library nil)
          (is (nil? (fleet/export-snapshot! facade nil)))
          (is (nil? (fleet/export-snapshot! facade id)))
          (is (= :missing-fleet (:error (fleet/delete! facade id))))
          (is (get-in (persisted/records! facade :fleets) [:fleets id]))
          (swap! (:state catalog) assoc :library library)
          (is (= id (get-in (fleet/export-snapshot! facade id) [:fleet :fleet/id]))))
        (testing "deleted references remain diagnostic and retained lists stay editable"
          (loadout/delete! (:shipyard.loadout/db sys) (:loadout/id class))
          (persisted/available! catalog (:weapon fixture/ids) false)
          (let [retained (assoc (fleet/record! facade id) :fleet/name "Renamed")
                snapshot (fleet/export-snapshot! facade id)]
            (is (= 2 (get-in snapshot [:fleet :fleet/entries (:loadout/id class)])))
            (is (nil? (get-in snapshot [:classes (:loadout/id class)])))
            (is (false? (get-in snapshot [:parts (:weapon fixture/ids) :part/present?])))
            (is (= retained (:fleet (fleet/put! facade retained :update))))))
        (testing "deletion removes owned entries and preserves shared references and settings"
          (is (= {:deleted id} (fleet/delete! facade id)))
          (is (nil? (fleet/record! facade id)))
          (is (store/read! database #(d/pull % [:part/id] [:part/key [library (:weapon fixture/ids)]])))
          (is (= [:supported] (store/read! database store/default-variant-preference)))
          (is (empty? (store/read! database #(d/q '[:find [?entry ...] :where [?entry :fleet-entry/quantity]] %))))))
      (finally (fixture/stop! started)))))
