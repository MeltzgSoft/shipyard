(ns shipyard.catalog.db
  "Catalog projections and transactional authoring over the shared metadata store."
  (:require [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.store.db :as store]
            [shipyard.store.transforms :as t]
            [shipyard.catalog.part :as part]
            [shipyard.regions.model :as regions]
            [shipyard.regions.registry :as registry]
            [shipyard.library.index :as index]
            [shipyard.part.orientation :as orientation]))

(defn from-parts
  "Immutable domain catalog, also useful for pure fixtures."
  [parts]
  {:parts (into {} (map (juxt :part/id identity)) parts)
   :registry (registry/discover registry/empty-registry (keep :part/paint-regions parts))})

(defn snapshot! [{:keys [store state]}]
  (store/read! store #(store/catalog-value % (:library @state))))

(defn region-registry! [{:keys [store state]}]
  (store/read! store #(store/registry-value % (:library @state))))

(defn part-context!
  "Read one present part and its shared layer registry from the same store snapshot.
  Unrelated parts and their masks are not materialized."
  [{:keys [store state]} part-id]
  (store/read! store
               (fn [db]
                 (let [library (:library @state)
                       shared (store/registry-value db library)
                       entity (d/pull db store/part-pattern [:part/key [library part-id]])]
                   {:registry shared
                    :part (when (and (:part/id entity) (not (false? (:part/present? entity))))
                            (t/part-value entity shared))}))))

(defn region-registry [database] (or (:registry database) registry/empty-registry))
(defn region-layers [database] (registry/ids (region-registry database)))
(defn part-regions [part] (:part/paint-regions part))

(defn part [database id]
  (let [value (get-in database [:parts id])]
    (when-not (false? (:part/present? value)) value)))

(defn browse [database {:keys [bundle class role q accepts-turrets?]}]
  (->> (vals (:parts database))
       (remove #(false? (:part/present? %)))
       (filter #(or (nil? bundle) (= bundle (:part/bundle %))))
       (filter #(or (nil? class) (= class (:part/class %))))
       (filter #(or (nil? role) (= role (:part/role-hint %))))
       (filter #(or (nil? accepts-turrets?) (= (boolean accepts-turrets?) (boolean (:part/accepts-turrets? %)))))
       (filter #(or (nil? q) (re-find (re-pattern (str "(?i)" (java.util.regex.Pattern/quote q))) (str (:part/name %)))))
       (sort-by :part/id)))

(defn bundles [database] (sort (set (keep :part/bundle (browse database {})))))
(defn classes
  ([database] (classes database nil))
  ([database bundle] (sort (set (keep :part/class (browse database {:bundle bundle}))))))
(defn roles [database] (sort-by name (set (keep :part/role-hint (browse database {})))))

(defn reingest! [{:keys [store state]} parts root]
  (let [lock (:lock store)]
    (locking lock
      (let [library (store/scan! store (or parts []) root)]
        (reset! state {:library library :root root})))))

(defn open! [store parts root]
  (let [catalog {:store store :state (atom {})}]
    (reingest! catalog parts root)
    catalog))

(defmethod ig/init-key :shipyard.catalog/db [_ {:keys [library store]}]
  (open! store (index/parts! library) (index/root! library)))

(defn- mutate! [{:keys [store state]} part-id f]
  (store/write! store
                (fn [conn]
                  (let [library (:library @state)
                        ref [:part/key [library part-id]]
                        entity (d/pull @conn store/part-pattern ref)]
                    (when-not (:part/present? entity)
                      (throw (ex-info "Part is unavailable" {:part-id part-id})))
                    (let [result (f conn library ref entity)]
                      (d/transact! conn [{:db/id ref :part/revision (inc (or (:part/revision entity) 0))}])
                      result)))))

(defn save-authoring! [catalog part-id {:keys [mounts part-role] :as value}]
  (mutate! catalog part-id
           (fn [conn _ ref entity]
             (let [ids (into {} (map (juxt :mount/id :mount/uid)) (:part/mounts entity))
                   mounts (mapv (fn [order mount] (assoc (dissoc mount :db/id) :mount/order order :mount/uid (or (ids (:mount/id mount)) (random-uuid)))) (range) mounts)]
               (d/transact! conn (store/retract-children @conn ref [:part/mounts]))
               (d/transact! conn [(cond-> {:db/id ref :part/mounts mounts}
                                    part-role (assoc :part/role-override part-role))])
               value))))

(defn save-mounts! [catalog part-id mounts]
  (save-authoring! catalog part-id {:mounts mounts})
  mounts)

(defn save-part-role! [catalog part-id role]
  (mutate! catalog part-id
           (fn [conn _ ref _]
             (d/transact! conn (if role [{:db/id ref :part/role-override role}]
                                   [[:db.fn/retractAttribute ref :part/role-override]]))
             role)))

(defn save-part-orientation! [catalog part-id value]
  (let [value (or (orientation/normalize-quaternion value)
                  (throw (ex-info "Invalid part orientation" {:type :invalid-orientation :part-id part-id})))]
    (mutate! catalog part-id (fn [conn _ ref _] (d/transact! conn [{:db/id ref :part/orientation value}]) value))))

(defn save-regions!
  ([catalog part-id value] (save-regions! catalog part-id value nil))
  ([catalog part-id value expected-revision]
   (when-not (regions/valid? value) (throw (ex-info "Invalid part regions" {})))
   (mutate! catalog part-id
            (fn [conn library _ entity]
              (let [shared (store/registry-value @conn library)
                    current (t/region (:part/regions entity) shared)
                    known (set (registry/ids shared))]
                (when (or (and expected-revision (not= expected-revision (or (:revision current) 0)))
                          (not-every? known (vals (:faces value))))
                  (throw (ex-info "Regions or shared layers changed before saving. Reopen the part." {})))
                (store/save-region! conn library part-id value)
                (assoc value :layer-definitions (select-keys (:layers shared) (:layers value))))))))

(defn edit-region-layer!
  [{:keys [store state]} part-id revision layer-revision action layer name]
  (store/write! store
                (fn [conn]
                  (let [library (:library @state)
                        shared (store/registry-value @conn library)
                        entity (d/pull @conn store/part-pattern [:part/key [library part-id]])
                        selected (t/region (:part/regions entity) shared)
                        result (registry/change shared layer-revision action layer name
                                                (when (= action "add") (str "layer:" (random-uuid))))]
                    (cond
                      (not= revision (or (:revision selected) 0)) {:error "Regions changed. Reopen this part before retrying."}
                      (:error result) result
                      :else
                      (do
                        (when (= action "delete")
                          (let [layer-ref [:layer/key [library layer]]
                                layer-eid (:db/id (d/pull @conn [:db/id] layer-ref))
                                affected (d/q '[:find ?part ?region ?mask :in $ ?layer
                                                :where [?mask :mask/layer ?layer]
                                                [?region :region/masks ?mask]
                                                [?part :part/regions ?region]] @conn layer-eid)]
                            (doseq [[part region mask] affected]
                              (let [r (:region/revision (d/pull @conn [:region/revision] region))
                                    p (:part/revision (d/pull @conn [:part/revision] part))]
                                (d/transact! conn [[:db/retractEntity mask]
                                                   {:db/id region :region/revision (inc (or r 0))}
                                                   {:db/id part :part/revision (inc (or p 0))}])))
                            (d/transact! conn [[:db.fn/retractAttribute layer-ref :layer/active-name]
                                               {:db/id layer-ref :layer/deleted? true}])))
                        (d/transact! conn (cond-> [{:library/id library :library/revision (:revision (:registry result))}]
                                            (not= action "delete")
                                            (conj (t/layer-tx library (:selected result)
                                                              (get-in result [:registry :layers (:selected result)])))))
                        {:selected (:selected result)
                         :regions (t/region (:part/regions (d/pull @conn store/part-pattern [:part/key [library part-id]]))
                                            (:registry result))}))))))

(def summary-pattern
  [:part/id :part/uid :part/name :part/bundle :part/class :part/role-hint :part/role-source
   :part/name-override :part/bundle-override :part/class-override :part/role-override
   :part/orientation :part/present? :part/renderable :part/variants :part/revision])

(defn listing! [{:keys [store state]}]
  (store/read! store
               (fn [db]
                 (let [library (:library @state)
                       ;; Only attachment labels and mask references: never face chunks or mount geometry.
                       pattern (conj summary-pattern
                                     {:part/mounts [:mount/kind :mount/accepts :mount/capacity]
                                      :part/regions [{:region/masks [:db/id]}]})]
                   {:parts (into {}
                                 (map (fn [entity]
                                        [(:part/id entity)
                                         (assoc (dissoc (t/part-value (dissoc entity :part/regions :part/mounts) nil) :part/mounts)
                                                :part/mount-summary (part/mount-summary (:part/mounts entity))
                                                :part/has-regions? (boolean (seq (get-in entity [:part/regions :region/masks]))))]))
                                 (when library (d/q '[:find [(pull ?part pattern) ...] :in $ ?library pattern
                                                      :where [?lib :library/id ?library] [?part :part/library ?lib]]
                                                    db library pattern)))}))))

(defn scene-snapshot!
  "Attachment catalog with dense region masks only for the requested scene parts."
  [{:keys [store state]} region-part-ids]
  (store/read! store
               (fn [db]
                 (let [library (:library @state)
                       shared (store/registry-value db library)
                       pattern (conj summary-pattern :part/source :part/accepts-turrets? {:part/mounts '[*]})
                       parts (into {} (map (fn [entity] [(:part/id entity) (t/part-value entity shared)]))
                                   (when library (d/q '[:find [(pull ?part pattern) ...] :in $ ?library pattern
                                                        :where [?lib :library/id ?library] [?part :part/library ?lib]]
                                                      db library pattern)))]
                   {:registry shared
                    :parts (reduce (fn [parts id]
                                     (if (contains? parts id)
                                       (assoc parts id (t/part-value (d/pull db store/part-pattern [:part/key [library id]]) shared))
                                       parts)) parts region-part-ids)}))))

(defn assembly-snapshot!
  "Catalog labels and attachment frames, without dense face masks."
  [catalog]
  (scene-snapshot! catalog []))

(defn summary! [{:keys [store state]} id]
  (store/read! store
               (fn [db]
                 (let [entity (d/pull db summary-pattern [:part/key [(:library @state) id]])]
                   (when (:part/present? entity) (t/part-value entity nil))))))

(defn save-metadata!
  "Apply one validated bulk edit atomically. Missing parts abort the whole edit."
  [{:keys [store state]} changes]
  (store/write! store
                (fn [conn]
                  (let [library (:library @state)
                        tx (mapv (fn [{:keys [id attribute value]}]
                                   (let [ref [:part/key [library id]]
                                         part (d/pull @conn [:part/present? :part/revision] ref)]
                                     (when-not (:part/present? part)
                                       (throw (ex-info "A selected part is unavailable. Refresh the table and retry." {})))
                                     {:db/id ref attribute value :part/revision (inc (or (:part/revision part) 0))})) changes)]
                    (d/transact! conn tx)))))
