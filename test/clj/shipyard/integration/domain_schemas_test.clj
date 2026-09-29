(ns shipyard.integration.domain-schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout.db :as classes]
            [shipyard.scheme.db :as schemes]
            [shipyard.ship.db :as ships]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.store.db :as store]))

(deftest rejected-domain-writes-preserve-snapshots-and-reopen
  (let [started (fixture/start!) sys (:system started)
        cat (:shipyard.catalog/db sys) database (:store cat)
        library (:library @(:state cat))
        class-db (:shipyard.loadout/db sys) scheme-db (:shipyard.scheme/db sys) ship-db (:shipyard.ship/db sys)
        hull (:hull fixture/ids) weapon (:weapon fixture/ids)
        material {:base [0.2 0.3 0.4] :metalness 0.5 :roughness 0.6}
        class {:loadout/id (random-uuid) :loadout/name "Class" :loadout/hull hull
               :loadout/slots {[[:weapon 0]] weapon}}
        scheme {:scheme/id (random-uuid) :scheme/name "Palette" :scheme/layers {"Primary" material}}
        face (apply str (repeat 72 "0")) mesh (apply str (repeat 64 "a"))
        group {:group/id (random-uuid) :group/name "Battery" :group/order 0
               :group/members [{:path [] :part-id hull} {:path [[:weapon 0]] :part-id weapon}]}
        ship {:ship/id (random-uuid) :ship/name "Ship" :ship/class (:loadout/id class)
              :ship/scheme (:scheme/id scheme)
              :ship/paint {:paint/groups [group] :paint/instances {[] {:part-id hull :material material}}
                           :paint/details {[] {:part-id hull :mesh-key mesh :faces {face material}}}}}]
    (try
      (testing "direct facade writes and nested values survive a real database reopen"
        (is (nil? (:error (classes/put! class-db class :create))))
        (is (nil? (:error (schemes/put! scheme-db scheme :create))))
        (is (nil? (:error (ships/put! ship-db ship :create))))
        (doseq [[facade kind record] [[class-db :loadouts class] [scheme-db :schemes scheme] [ship-db :ships ship]]]
          (is (= record (get-in (persisted/records! facade kind) [kind (get record (case kind :loadouts :loadout/id :schemes :scheme/id :ships :ship/id))])))))
      (testing "invalid updates bypassing HTTP preserve every published and durable record"
        (let [before (mapv #(store/read! database (fn [db] (store/records-value db %))) [:loadouts :schemes :ships])
              catalog-before (catalog/snapshot! cat)]
          (doseq [[kind invalid code]
                  [[:loadouts (assoc class :loadout/slots {[[:weapon -1]] weapon}) :invalid-loadout]
                   [:loadouts (assoc class :unexpected true) :invalid-loadout]
                   [:schemes (assoc-in scheme [:scheme/layers "Primary" :glow] ##NaN) :invalid-scheme]
                   [:schemes (dissoc scheme :scheme/name) :invalid-scheme]
                   [:ships (assoc-in ship [:ship/paint :paint/groups] [group group]) :invalid-ship]
                   [:ships (assoc-in ship [:ship/paint :paint/details [] :faces face :base] [2 0 0]) :invalid-ship]
                   [:ships (assoc-in ship [:ship/paint :paint/instances [] :part-id] "") :invalid-ship]]]
            (is (= code (:error (store/put-record! database library kind invalid :update)))))
          (is (= before [(classes/snapshot! class-db) (schemes/snapshot! scheme-db) (ships/snapshot! ship-db)]))
          (is (= before (mapv #(persisted/records! database %) [:loadouts :schemes :ships])))
          (is (= catalog-before (catalog/snapshot! cat)))
          (is (= catalog-before (persisted/catalog! cat)))))
      (testing "invalid regions never replace the existing source-bound masks"
        (let [region {:version 2 :revision 0 :mesh-key mesh :layers ["Primary" "Secondary"]
                      :layer-definitions {} :faces {face "Secondary"}}]
          (catalog/save-regions! cat hull region)
          (let [before (catalog/snapshot! cat)]
            (is (thrown? Exception (catalog/save-regions! cat hull (assoc-in region [:faces face] "Unknown"))))
            (is (= before (catalog/snapshot! cat)))
            (is (= before (persisted/catalog! cat))))))
      (finally (fixture/stop! started)))))
