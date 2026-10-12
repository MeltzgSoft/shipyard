(ns shipyard.fleet.db
  "Library-scoped print lists over the shared transaction boundary."
  (:require [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.fleet.transforms :as t]
            [shipyard.store.db :as store]))

(defmethod ig/init-key :shipyard.fleet/db [_ value]
  (assoc value :lock (get-in value [:store :lock])))

(defn snapshot! [{:keys [store catalog]}]
  (store/read! store
               (fn [db]
                 (let [library (:library @(:state catalog))]
                   {:version 1 :fleets (into {} (filter (fn [[_ record]] (= library (:fleet/library record))))
                                             (:fleets (store/records-value db :fleets)))}))))

(defn record! [{:keys [store catalog]} id]
  (store/read! store
               (fn [db]
                 (let [record (store/record-value db :fleets id)]
                   (when (= (:library @(:state catalog)) (:fleet/library record)) record)))))

(defn put! [{:keys [store catalog]} record mode]
  (let [lock (:lock store)]
    (locking lock
      (store/put-record! store (:library @(:state catalog)) :fleets record mode))))

(defn create! [facade name]
  (put! facade {:fleet/id (random-uuid) :fleet/name name
                :fleet/library (:library @(:state (:catalog facade)))
                :fleet/entries {} :fleet/extras {}
                :fleet/variant-preference t/default-variant-preference} :create))

(defn delete! [{:keys [store] :as facade} id]
  (let [lock (:lock store)]
    (locking lock
      (if (record! facade id) (store/delete-record! store :fleets id)
          {:error :missing-fleet}))))

(defn use-default! [{:keys [store catalog]} id]
  (try
    (store/write! store
                  (fn [conn]
                    (let [record (store/record-value @conn :fleets id)
                          library (:library @(:state catalog))]
                      (if (and record (= library (:fleet/library record)))
                        (let [record (assoc record :fleet/variant-preference
                                            (store/default-variant-preference @conn))]
                          (store/put-fleet! conn library record)
                          {:fleet record})
                        {:error :missing-fleet}))))
    (catch Exception e {:error :store-write-failed :message (ex-message e)})))

(defn export-snapshot! [{:keys [store catalog]} id]
  (store/read! store
               (fn [db]
                 (let [library (:library @(:state catalog))
                       fleet (store/record-value db :fleets id)
                       library-entity (d/pull db '[*] [:library/id library])]
                   (when (and library fleet (= library (:fleet/library fleet)))
                     {:fleet fleet :library (select-keys library-entity [:library/id :library/root])
                      :classes (into {} (keep (fn [class]
                                                (when-let [value (store/record-value db :loadouts class)] [class (assoc value :loadout/library (get-in (d/pull db [{:loadout/library [:library/id]}] [:loadout/id class]) [:loadout/library :library/id]))])))
                                     (keys (:fleet/entries fleet)))
                      :parts (into {} (map (fn [part]
                                             [(:part/id part)
                                              (assoc (select-keys part [:part/id :part/present? :part/name :part/variants])
                                                     :part/library library
                                                     :part/sources (into {} (map (fn [source]
                                                                                   [(:source/variant source)
                                                                                    (assoc (dissoc source :db/id :source/part)
                                                                                           :source/path (t/source-path (:source/path source) java.io.File/separatorChar)
                                                                                           :source/content (get-in source [:source/content :mesh/sha]))]))
                                                                         (:part/sources part)))]))
                                   (store/entities db :part/library (:db/id library-entity)
                                                   '[* {:part/sources [* {:source/content [:mesh/sha]}]}]))})))))
