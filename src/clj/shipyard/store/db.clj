(ns shipyard.store.db
  "One application-owned Datalevin store. All writes and snapshot materialization
  share this boundary; domain functions never receive live database handles."
  (:require [babashka.fs :as fs]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.store.schema :as schema]
            [shipyard.store.transforms :as t]
            [shipyard.store.legacy :as legacy]
            [shipyard.regions.registry :as registry]
            [shipyard.regions.model :as regions]
            [shipyard.regions.migration :as migration]
            [shipyard.part.orientation :as orientation]
            [shipyard.loadout.transforms :as loadout]
            [shipyard.scheme.transforms :as scheme]
            [shipyard.system :as system]))

(def part-pattern
  '[* {:part/mounts [*] :part/regions [* {:region/content [:mesh/sha]
                                          :region/masks [* {:mask/layer [:layer/id]
                                                            :mask/chunks [*]}]}]}])
(def loadout-pattern
  '[* {:loadout/hull [:part/id] :loadout/scheme [:scheme/id]
       :loadout/slots [* {:slot/part [:part/id]}]}])
(def scheme-pattern
  '[* {:scheme/roles [*] :scheme/layers [* {:binding/layer [:layer/id]}]
       :scheme/groups [* {:group/members [* {:membership/target [:db/id]}]}]
       :scheme/targets [* {:target/part [:part/id]
                           :target/details [* {:detail/content [:mesh/sha]
                                               :detail/chunks [*]}]}]}])

(defn open! [directory]
  (let [directory (str (fs/normalize (fs/absolutize directory)))
        conn (d/get-conn directory schema/schema {:validate-data? true :closed-schema? true})
        store {:conn conn :lock conn :directory directory}]
    (try
      (let [version (:store/version (d/pull @conn '[*] [:store/key "shipyard"]))]
        (when (and version (not= 1 version))
          (throw (ex-info "Unsupported Shipyard database version" {:version version :directory directory})))
        (when-not version (d/transact! conn [{:store/key "shipyard" :store/version 1}])))
      store
      (catch Exception e (d/close conn) (throw e)))))

(defn close! [{:keys [conn]}] (d/close conn))

(defmethod ig/init-key :shipyard.store/db [_ {:keys [data-home directory legacy-files]}]
  (assoc (open! (or directory (fs/path (or data-home (system/data-home!)) "shipyard" "database")))
         :legacy-files legacy-files))
(defmethod ig/halt-key! :shipyard.store/db [_ store] (close! store))

(defn read! [{:keys [conn lock]} f]
  (locking lock (f @conn)))

(defn write! [{:keys [conn lock]} f]
  (locking lock
    (d/with-transaction [tx conn]
      (f tx))))

(defn entities [db attr value pattern]
  (mapv #(d/pull db pattern %)
        (d/q '[:find [?e ...] :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn library-location [root]
  (str (fs/normalize (fs/absolutize root))))

(defn library-id [db root]
  (:library/id (d/pull db [:library/id] [:library/root (library-location root)])))

(defn registry-value [db library]
  (if-not library registry/empty-registry
          (let [entity (d/pull db '[*] [:library/id library])]
            (t/layer-registry (:library/revision entity)
                              (entities db :layer/library (:db/id entity) '[*])))))

(defn catalog-value [db library]
  (let [shared (registry-value db library)
        eid (:db/id (d/pull db [:db/id] [:library/id library]))]
    {:library library :registry shared
     :parts (if-not eid {}
                    (into {} (map (fn [entity] [(:part/id entity) (t/part-value entity shared)]))
                          (entities db :part/library eid part-pattern)))}))

(defn part-ref! [conn library path]
  (when-not (d/pull @conn [:db/id] [:part/key [library path]])
    (d/transact! conn [{:part/uid (random-uuid) :part/key [library path] :part/id path
                        :part/library [:library/id library] :part/present? false :part/revision 0}]))
  [:part/uid (:part/uid (d/pull @conn [:part/uid] [:part/key [library path]]))])

(defn- layer-ref! [conn library id]
  (when-not (d/pull @conn [:db/id] [:layer/key [library id]])
    ;; A palette may retain an unknown/deleted layer without reviving it.
    (d/transact! conn [{:layer/key [library id] :layer/id id :layer/library [:library/id library]
                        :layer/deleted? true :layer/builtin? false}]))
  [:layer/key [library id]])

(defn- content! [conn sha]
  (when-not (d/pull @conn [:db/id] [:mesh/sha sha])
    (d/transact! conn [{:mesh/sha sha}])))

(defn retract-children [db ref attrs]
  (let [entity (d/pull db (vec attrs) ref)]
    (vec (mapcat (fn [attr]
                   (let [value (get entity attr)]
                     (map (fn [child] [:db/retractEntity (:db/id child)])
                          (if (map? value) [value] value)))) attrs))))

(defn save-region! [conn library path value]
  (content! conn (:mesh-key value))
  (let [ref (part-ref! conn library path)]
    (d/transact! conn (conj (retract-children @conn ref [:part/regions])
                            {:db/id ref :part/regions (t/region-tx library value)}))))

(def scan-attributes
  [:part/name :part/bundle :part/class :part/role-hint :part/role-source :part/source
   :part/renderable :part/mesh-key :part/tris :part/weapons? :part/turrets? :part/accepts-turrets?])

(defn scan!
  "Import each part once and refresh observed facts without replacing authored state.
  A failed legacy read aborts the entire library import."
  [store parts root]
  (when root
    (write! store
            (fn [conn]
              (let [library (or (library-id @conn root) (random-uuid))
                    existing (d/pull @conn '[*] [:library/id library])
                    imported? (:library/imported? existing)
                    authored (into {} (keep (fn [part]
                                              (when-not (:part/imported? (d/pull @conn '[:part/imported?] [:part/key [library (:part/id part)]]))
                                                [(:part/id part) (legacy/part! root (:part/id part))]))) parts)
                    shared (registry/discover (if imported? (registry-value @conn library) (legacy/layers! root))
                                              (keep #(migration/regions (:part/paint-regions %)) (vals authored)))]
                (d/transact! conn [{:library/id library :library/root (library-location root)
                                    :library/revision (:revision shared)}])
                (d/transact! conn (t/layers-tx library shared))
                (doseq [id (:deleted shared)] (layer-ref! conn library id))
                (doseq [old (entities @conn :part/library (:db/id (d/pull @conn [:db/id] [:library/id library])) [:db/id])]
                  (d/transact! conn [{:db/id (:db/id old) :part/present? false}]))
                (doseq [part parts]
                  (let [path (:part/id part) ref (part-ref! conn library path)
                        sc (get authored path)
                        old (d/pull @conn '[*] ref)
                        removed (for [attr (conj scan-attributes :part/variants)
                                      :when (contains? old attr)]
                                  [:db.fn/retractAttribute ref attr])
                        value (into {} (remove (comp nil? val)) (select-keys part scan-attributes))
                        value (cond-> (assoc value :db/id ref :part/present? true :part/imported? true
                                             :part/variants (vec (:part/variants part)))
                                (contains? authored path)
                                (merge (cond-> {:part/mounts (mapv (fn [order mount] (assoc mount :mount/uid (random-uuid) :mount/order order)) (range) (:mounts sc))}
                                         (:part/role sc) (assoc :part/role-override (:part/role sc))
                                         (:part/orientation sc) (assoc :part/orientation
                                                                       (or (orientation/normalize-quaternion (:part/orientation sc))
                                                                           (throw (ex-info "Invalid imported orientation" {:part path}))))
                                         (seq (apply dissoc sc [:mounts :part/role :part/orientation :part/paint-regions :shipyard/version]))
                                         (assoc :migration/extra (apply dissoc sc [:mounts :part/role :part/orientation :part/paint-regions :shipyard/version])))))]
                    (d/transact! conn (conj (vec removed) value))
                    (doseq [source (:part/sources old)]
                      (d/transact! conn [{:db/id (:db/id source) :source/present? false}]))
                    (doseq [variant (:part/variants part)]
                      (let [source (fs/path root path (str (name variant) ".stl"))]
                        (when (fs/regular-file? source)
                          (let [key [(:part/uid old) variant]
                                sha (when (= variant (:part/source part)) (:part/mesh-key part))]
                            (when sha (content! conn sha))
                            (when (d/pull @conn [:db/id] [:source/key key])
                              (d/transact! conn [[:db.fn/retractAttribute [:source/key key] :source/content]]))
                            (d/transact! conn [{:db/id ref
                                                :part/sources [(cond-> {:source/key key :source/part ref :source/present? true
                                                                        :source/variant variant :source/path (str (fs/path path (str (name variant) ".stl")))
                                                                        :source/size (fs/size source)
                                                                        :source/mtime (.toMillis ^java.nio.file.attribute.FileTime (fs/last-modified-time source))}
                                                                 sha (assoc :source/content [:mesh/sha sha]))]}])))))
                    (when-let [region (:part/paint-regions sc)]
                      (save-region! conn library path (reduce regions/without-layer (migration/regions region) (:deleted shared))))))
                (d/transact! conn [{:library/id library :library/imported? true}])
                library)))))

(defn records-value [db kind]
  (let [[id-attr deleted pattern project] (case kind
                                            :schemes [:scheme/id :scheme/deleted? scheme-pattern t/scheme-value]
                                            :loadouts [:loadout/id :loadout/deleted? loadout-pattern t/loadout-value])]
    {:version 1 kind (into {} (keep (fn [eid]
                                      (let [entity (d/pull db pattern eid)]
                                        (when-not (get entity deleted) [(get entity id-attr) (project entity)]))))
                           (d/q '[:find [?e ...] :in $ ?a :where [?e ?a]] db id-attr))}))

(defn- ensure-scheme! [conn id]
  (when-not (d/pull @conn [:db/id] [:scheme/id id])
    (d/transact! conn [{:scheme/id id :scheme/deleted? true}])))

(defn put-loadout! [conn library record]
  (let [library (or (get-in (d/pull @conn [{:loadout/library [:library/id]}] [:loadout/id (:loadout/id record)]) [:loadout/library :library/id]) library)
        paths (cons (:loadout/hull record) (vals (:loadout/slots record)))
        parts (into {} (map (fn [path] [path (part-ref! conn library path)])) paths)
        id (:loadout/id record)
        old (d/pull @conn '[*] [:loadout/id id])]
    (when-let [scheme (:loadout/scheme record)] (ensure-scheme! conn scheme))
    (d/transact! conn
                 (concat (retract-children @conn [:loadout/id id] [:loadout/slots])
                         (when (:loadout/scheme old) [[:db.fn/retractAttribute [:loadout/id id] :loadout/scheme]])
                         [(cond-> {:loadout/id id :loadout/name (:loadout/name record)
                                   :loadout/deleted? false :loadout/revision (inc (or (:loadout/revision old) 0))
                                   :loadout/library [:library/id library]
                                   :loadout/hull (get parts (:loadout/hull record))
                                   :loadout/slots (mapv (fn [[path part]] {:slot/path path :slot/part (get parts part)})
                                                        (:loadout/slots record))}
                            (:loadout/scheme record) (assoc :loadout/scheme [:scheme/id (:loadout/scheme record)]))]))))

(defn put-scheme! [conn library record]
  (let [library (or (get-in (d/pull @conn [{:scheme/library [:library/id]}] [:scheme/id (:scheme/id record)]) [:scheme/library :library/id]) library)
        paths (concat (map :part-id (vals (:scheme/instances record)))
                      (map :part-id (vals (:scheme/details record)))
                      (map :part-id (mapcat :group/members (:scheme/groups record))))
        parts (into {} (map (fn [path] [path (part-ref! conn library path)])) paths)
        id (:scheme/id record) old (d/pull @conn '[*] [:scheme/id id])]
    (doseq [layer (keys (:scheme/layers record))] (layer-ref! conn library layer))
    (doseq [detail (vals (:scheme/details record))] (content! conn (:mesh-key detail)))
    ;; Resolve identity upserts after removing the old owned graph, inside the
    ;; enclosing transaction, so no parent refs point at retracted entities.
    (d/transact! conn (retract-children @conn [:scheme/id id]
                                        [:scheme/groups :scheme/roles :scheme/layers :scheme/targets]))
    (d/transact! conn [(cond-> (assoc (t/scheme-tx library parts record)
                                      :scheme/revision (inc (or (:scheme/revision old) 0)))
                         library (assoc :scheme/library [:library/id library]))])))

(defn import-records! [store library]
  (when library
    (write! store
            (fn [conn]
              (when-not (:store/imported? (d/pull @conn '[*] [:store/key "shipyard"]))
                (let [records (legacy/records! (fs/parent (:directory store)) (:legacy-files store))]
                  (doseq [record (vals (:schemes records))] (put-scheme! conn library (migration/scheme record)))
                  (doseq [record (vals (:loadouts records))] (put-loadout! conn library record))
                  (d/transact! conn [{:store/key "shipyard" :store/imported? true}])))))))

(defn put-record! [store library kind record mode]
  (try
    (write! store
            (fn [conn]
              (let [record (if (= kind :schemes) (migration/scheme record) record)
                    operation (if (= kind :schemes) scheme/put-record loadout/put-record)
                    result (operation (records-value @conn kind) record mode)]
                (if (:error result) result
                    (do
                      (when (and (nil? library)
                                 (or (= kind :loadouts) (seq (:scheme/layers record))
                                     (seq (:scheme/instances record)) (seq (:scheme/details record))
                                     (seq (mapcat :group/members (:scheme/groups record)))))
                        (throw (ex-info "Select a library before saving part or layer references" {})))
                      ((if (= kind :schemes) put-scheme! put-loadout!) conn library record)
                      (dissoc result :store))))))
    (catch Exception e {:error :store-write-failed :message (str "Could not save metadata. " (ex-message e))})))

(defn delete-record! [store kind id]
  (try
    (write! store
            (fn [conn]
              (let [result ((if (= kind :schemes) scheme/delete-record loadout/delete-record)
                            (records-value @conn kind) id)
                    [key deleted] (if (= kind :schemes) [:scheme/id :scheme/deleted?] [:loadout/id :loadout/deleted?])]
                (if (:error result) result
                    (do (d/transact! conn [{key id deleted true}]) (dissoc result :store))))))
    (catch Exception e {:error :store-write-failed :message (str "Could not delete metadata. " (ex-message e))})))
