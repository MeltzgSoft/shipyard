(ns shipyard.integration.metadata-store-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.library.scan :as scan]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.regions.migration :as migration]
            [shipyard.store.db :as store]
            [shipyard.system :as system]))

(deftest failed-import-can-be-repaired-and-retried
  (let [temp (fs/create-temp-dir {:prefix "shipyard-import-"}) root (fs/path temp "library")
        database (store/open! (fs/path temp "database")) id (:weapon fixture/ids)]
    (try
      (fixture/library! root)
      (let [file (sidecar/sidecar-file (str root) id) original (slurp file)
            parts (vec (scan/scan! (fs/file root)))]
        (spit file "{:shipyard/version 99}")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot import"
                              (catalog/open! database parts (str root))))
        (is (nil? (store/read! database #(store/library-id % (str root)))))
        (is (= "{:shipyard/version 99}" (slurp file)))
        (spit file original)
        (let [cat (catalog/open! database parts (str root)) snapshot (catalog/snapshot! cat)]
          (is (= (mapv #(cond-> % (:mount/accepts %) (update :mount/accepts vec)) fixture/weapon-mounts) (:part/mounts (catalog/part snapshot id))))
          (spit file "{invalid after successful import")
          (catalog/reingest! cat parts (str root))
          (is (= snapshot (catalog/snapshot! cat)))
          (is (= snapshot (persisted/catalog! cat)))))
      (finally (store/close! database) (fs/delete-tree temp)))))

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

(deftest custom-legacy-locations-import-with-the-library-atomically
  (let [temp (fs/create-temp-dir {:prefix "shipyard-legacy-records-"})
        root (fs/path temp "library") old-home (fs/path temp "old-data")
        cfg (system/persistence-config {:shipyard.loadout/db {:data-home old-home}
                                        :shipyard.scheme/db {:data-home old-home}})
        files (get-in cfg [:shipyard.store/db :legacy-files])
        database (assoc (store/open! (fs/path temp "new-database")) :legacy-files files)
        id (random-uuid) record {:loadout/id id :loadout/name "Imported"
                                 :loadout/hull (:hull fixture/ids) :loadout/slots {}}]
    (try
      (fixture/library! root)
      (fs/create-dirs (fs/parent (:loadouts files)))
      (spit (:loadouts files) (pr-str {:version 1 :loadouts {id record}}))
      (spit (:schemes files) "{:version 1 :schemes {}} {}")
      (let [parts (vec (scan/scan! (fs/file root)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot import"
                              (catalog/open! database parts (str root))))
        (is (nil? (store/read! database #(store/library-id % (str root)))))
        (is (empty? (:loadouts (persisted/records! database :loadouts))))
        (spit (:schemes files) "{:version 1 :schemes {}}")
        (let [cat (catalog/open! database parts (str root))]
          (is (= record (get-in (persisted/records! database :loadouts) [:loadouts id])))
          (spit (:loadouts files) "{invalid after import")
          (catalog/reingest! cat parts (str root))
          (is (= record (get-in (persisted/records! database :loadouts) [:loadouts id])))))
      (finally (store/close! database) (fs/delete-tree temp)))))

(deftest imported-tombstones-do-not-revive-old-mask-assignments
  (let [layer (migration/legacy-id "Trim") id (:weapon fixture/ids)
        region {:mesh-key (apply str (repeat 64 "a")) :revision 1
                :layers ["Primary" "Secondary" "Trim"] :faces {(apply str (repeat 72 "0")) "Trim"}}
        started (fixture/start! false
                                (fn [root]
                                  (fixture/library! root)
                                  (sidecar/update-sidecar! (str root) id assoc :part/paint-regions region)
                                  (spit (str (fs/path root "shipyard-layers.edn"))
                                        (pr-str {:version 1 :revision 2 :layers {} :deleted #{layer}}))
                                  root))
        cat (:shipyard.catalog/db (:system started))]
    (try
      (let [snapshot (persisted/catalog! cat)]
        (is (= #{layer} (get-in snapshot [:registry :deleted])))
        (is (empty? (get-in snapshot [:parts id :part/paint-regions :faces])))
        (is (= ["Primary" "Secondary"] (get-in snapshot [:parts id :part/paint-regions :layers]))))
      (finally (fixture/stop! started)))))
