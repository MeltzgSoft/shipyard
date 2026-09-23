(ns shipyard.persistence-fixture
  "Read actual durable snapshots through an independently opened Datalevin copy."
  (:require [babashka.fs :as fs]
            [datalevin.core :as d]
            [shipyard.store.db :as store]))

(defn persisted! [database f]
  (let [database (or (:store database) database)
        dir (fs/create-temp-dir {:prefix "shipyard-reopen-"})]
    (try
      (store/read! database #(d/copy % (str dir)))
      (let [reopened (store/open! dir)]
        (try (store/read! reopened f) (finally (store/close! reopened))))
      (finally (fs/delete-tree dir)))))

(defn records! [facade kind]
  (persisted! facade #(store/records-value % kind)))

(defn catalog! [catalog]
  (persisted! catalog #(store/catalog-value % (:library @(:state catalog)))))

(defn authored! [catalog id]
  (let [part (get-in (catalog! catalog) [:parts id])]
    (cond-> {:mounts (mapv #(cond-> % (:mount/accepts %) (update :mount/accepts set)) (:part/mounts part))}
      (:part/role-override part) (assoc :part/role (:part/role-override part))
      (:part/orientation part) (assoc :part/orientation (:part/orientation part))
      (:part/paint-regions part) (assoc :part/paint-regions (:part/paint-regions part)))))

(defn available! [catalog id present?]
  (store/write! (:store catalog)
                (fn [conn]
                  (d/transact! conn [{:db/id [:part/key [(:library @(:state catalog)) id]]
                                      :part/present? present?}]))))

(defn scheme-revision!
  "Set an actual stored revision and return its previous value. MAX_VALUE makes
  the next scheme transaction fail after its first mutation, exercising rollback."
  [facade id revision]
  (store/write! (:store facade)
                (fn [conn]
                  (let [ref [:scheme/id id]
                        before (:scheme/revision (d/pull @conn [:scheme/revision] ref))]
                    (d/transact! conn [{:db/id ref :scheme/revision revision}])
                    before))))
