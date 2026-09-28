(ns shipyard.integration.metadata-store-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.store.db :as store]))

(deftest snapshots-and-shared-layer-transactions
  (let [started (fixture/start!) cat (:shipyard.catalog/db (:system started))
        database (:store cat) a (:weapon fixture/ids) b (:weapon-alt fixture/ids)
        mesh (apply str (repeat 64 "a")) face (apply str (repeat 72 "0"))]
    (try
      (catalog/edit-region-layer! cat a 0 0 "add" nil "Trim")
      (let [shared (catalog/region-registry (catalog/snapshot! cat))
            layer (first (keys (:layers shared)))
            region {:version 2 :mesh-key mesh :revision 1 :layers ["Primary" "Secondary" layer]
                    :layer-definitions (:layers shared) :faces {face layer}}]
        (doseq [id [a b]] (catalog/save-regions! cat id region))
        (let [before (catalog/snapshot! cat)]
          (catalog/edit-region-layer! cat a 1 1 "rename" layer "Accent")
          (is (= "Trim" (get-in before [:registry :layers layer :name])))
          (is (= "Accent" (get-in (persisted/catalog! cat) [:registry :layers layer :name])))
          (is (= (mapv #(get-in before [:parts % :part/paint-regions :faces]) [a b])
                 (mapv #(get-in (catalog/snapshot! cat) [:parts % :part/paint-regions :faces]) [a b]))))
        (let [before (catalog/snapshot! cat)]
          (is (thrown? Exception
                       (store/write! database
                                     (fn [conn]
                                       (catalog/edit-region-layer! (assoc-in cat [:store :conn] conn) a 1 2 "delete" layer nil)
                                       (throw (ex-info "Abort after updating multiple masks" {}))))))
          (is (= before (catalog/snapshot! cat)))
          (is (= before (persisted/catalog! cat))))
        (catalog/edit-region-layer! cat a 1 2 "delete" layer nil)
        (doseq [id [a b]]
          (is (empty? (get-in (persisted/catalog! cat) [:parts id :part/paint-regions :faces]))))
        (is (= 1 (store/read! database #(d/q '[:find (count ?e) . :in $ ?id :where [?e :layer/id ?id]] % layer))))
        (is (contains? (get-in (persisted/catalog! cat) [:registry :deleted]) layer)))
      (finally (fixture/stop! started)))))
