(ns shipyard.store.db
  "One application-owned Datalevin store. All writes and snapshot materialization
  share this boundary; domain functions never receive live database handles."
  (:require [babashka.fs :as fs]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.store.schema :as schema]
            [shipyard.store.transforms :as t]
            [shipyard.regions.registry :as registry]
            [shipyard.loadout.transforms :as loadout]
            [shipyard.scheme.transforms :as scheme]
            [shipyard.ship.transforms :as ship]
            [shipyard.system :as system]))

(def part-pattern
  '[* {:part/mounts [*] :part/regions [* {:region/content [:mesh/sha]
                                          :region/masks [* {:mask/layer [:layer/id]
                                                            :mask/chunks [*]}]}]}])
(def scheme-pattern
  '[* {:scheme/layers [* {:binding/layer [:layer/id]}]}])

(def paint-pattern
  '[* {:paint/layers [* {:binding/layer [:layer/id]}]
       :paint/groups [* {:group/members [* {:membership/target [:db/id]}]}]
       :paint/targets [* {:target/part [:part/id]
                          :target/details [* {:detail/content [:mesh/sha] :detail/chunks [*]}]}]}])
(def loadout-pattern
  '[* {:loadout/hull [:part/id]
       :loadout/slots [* {:slot/part [:part/id]}]}])
(def ship-pattern
  ['* {:ship/class [:loadout/id] :ship/scheme [:scheme/id] :ship/paint paint-pattern}])

(defn open! [directory]
  (let [directory (str (fs/normalize (fs/absolutize directory)))
        ;; Own this handle: closing a temporary lookup must not close a live app.
        conn (d/create-conn directory schema/schema {:validate-data? true :closed-schema? true})
        store {:conn conn :lock conn :directory directory}]
    (try
      (let [version (:store/version (d/pull @conn '[*] [:store/key "shipyard"]))]
        (when (and version (not= 1 version))
          (throw (ex-info "Unsupported Shipyard database version" {:version version :directory directory})))
        (when-not version (d/transact! conn [{:store/key "shipyard" :store/version 1}])))
      store
      (catch Exception e (d/close conn) (throw e)))))

(defn close! [{:keys [conn]}] (d/close conn))

(defmethod ig/init-key :shipyard.store/db [_ {:keys [data-home directory]}]
  (open! (or directory (fs/path (or data-home (system/data-home!)) "shipyard" "database"))))
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
  "Refresh observed files without replacing authored database state."
  [store parts root]
  (when root
    (write! store
            (fn [conn]
              (let [library (or (library-id @conn root) (random-uuid))
                    shared (registry-value @conn library)]
                (d/transact! conn [{:library/id library :library/root (library-location root)
                                    :library/revision (:revision shared)}])
                (d/transact! conn (t/layers-tx library shared))
                (doseq [old (entities @conn :part/library (:db/id (d/pull @conn [:db/id] [:library/id library])) [:db/id])]
                  (d/transact! conn [{:db/id (:db/id old) :part/present? false}]))
                (doseq [part parts]
                  (let [path (:part/id part) ref (part-ref! conn library path)
                        old (d/pull @conn '[*] ref)
                        removed (for [attr (conj scan-attributes :part/variants)
                                      :when (contains? old attr)]
                                  [:db.fn/retractAttribute ref attr])
                        value (into {} (remove (comp nil? val)) (select-keys part scan-attributes))]
                    (d/transact! conn (conj (vec removed)
                                            (assoc value :db/id ref :part/present? true
                                                   :part/variants (vec (:part/variants part)))))
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
                                                                 sha (assoc :source/content [:mesh/sha sha]))]}])))))))
                library)))))

(defn- record-spec [kind]
  (case kind
    :schemes [:scheme/id :scheme/deleted? scheme-pattern t/scheme-value]
    :loadouts [:loadout/id :loadout/deleted? loadout-pattern t/loadout-value]
    :ships [:ship/id :ship/deleted? ship-pattern t/ship-value]))

(defn record-value [db kind id]
  (when id
    (let [[id-attr deleted pattern project] (record-spec kind)
          entity (d/pull db pattern [id-attr id])]
      (when (and (get entity id-attr) (not (get entity deleted))) (project entity)))))

(defn records-value [db kind]
  (let [[id-attr deleted pattern project] (record-spec kind)]
    {:version 1 kind (into {} (keep (fn [eid]
                                      (let [entity (d/pull db pattern eid)]
                                        (when-not (get entity deleted) [(get entity id-attr) (project entity)]))))
                           (d/q '[:find [?e ...] :in $ ?a :where [?e ?a]] db id-attr))}))

(defn ship-summaries [db]
  (into {} (keep (fn [entity]
                   (when-not (:ship/deleted? entity)
                     [(:ship/id entity) (select-keys (t/ship-value entity) [:ship/id :ship/name :ship/class :ship/scheme])])))
        (d/q '[:find [(pull ?ship [:ship/id :ship/name :ship/deleted? {:ship/class [:loadout/id] :ship/scheme [:scheme/id]}]) ...]
               :where [?ship :ship/id]] db)))

(defn scheme-summaries [db]
  (into {} (keep (fn [entity] (when-not (:scheme/deleted? entity)
                                [(:scheme/id entity) (select-keys entity [:scheme/id :scheme/name])])))
        (d/q '[:find [(pull ?scheme [:scheme/id :scheme/name :scheme/deleted?]) ...]
               :where [?scheme :scheme/id]] db)))

(defn scheme-palette [db id]
  (when id
    (let [entity (d/pull db '[:scheme/id :scheme/name :scheme/deleted?
                              {:scheme/layers [* {:binding/layer [:layer/id]}]}] [:scheme/id id])]
      (when (and (:scheme/id entity) (not (:scheme/deleted? entity)))
        (select-keys (t/scheme-value entity) [:scheme/id :scheme/name :scheme/layers])))))

(defn- ensure-scheme! [conn id]
  (when-not (d/pull @conn [:db/id] [:scheme/id id])
    (d/transact! conn [{:scheme/id id :scheme/deleted? true}])))

(defn- paint-refs! [conn library record]
  (let [paths (concat (map :part-id (vals (:paint/instances record)))
                      (map :part-id (vals (:paint/details record)))
                      (map :part-id (mapcat :group/members (:paint/groups record))))]
    (doseq [layer (keys (:paint/layers record))] (layer-ref! conn library layer))
    (doseq [detail (vals (:paint/details record))] (content! conn (:mesh-key detail)))
    (into {} (map (fn [path] [path (part-ref! conn library path)])) paths)))

(defn put-loadout! [conn library record]
  (let [library (or (get-in (d/pull @conn [{:loadout/library [:library/id]}] [:loadout/id (:loadout/id record)]) [:loadout/library :library/id]) library)
        paths (cons (:loadout/hull record) (vals (:loadout/slots record)))
        parts (into {} (map (fn [path] [path (part-ref! conn library path)])) paths)
        id (:loadout/id record)
        old (d/pull @conn '[*] [:loadout/id id])]
    (d/transact! conn
                 (concat (retract-children @conn [:loadout/id id] [:loadout/slots])
                         [{:loadout/id id :loadout/name (:loadout/name record)
                           :loadout/deleted? false :loadout/revision (inc (or (:loadout/revision old) 0))
                           :loadout/library [:library/id library]
                           :loadout/hull (get parts (:loadout/hull record))
                           :loadout/slots (mapv (fn [[path part]] {:slot/path path :slot/part (get parts part)})
                                                (:loadout/slots record))}]))))

(defn put-scheme! [conn library record]
  (let [library (or (get-in (d/pull @conn [{:scheme/library [:library/id]}] [:scheme/id (:scheme/id record)]) [:scheme/library :library/id]) library)
        id (:scheme/id record) old (d/pull @conn '[*] [:scheme/id id])]
    (doseq [layer (keys (:scheme/layers record))] (layer-ref! conn library layer))
    ;; Resolve identity upserts after removing the old owned graph, inside the
    ;; enclosing transaction, so no parent refs point at retracted entities.
    (d/transact! conn (retract-children @conn [:scheme/id id]
                                        [:scheme/layers]))
    (d/transact! conn [(cond-> (assoc (t/scheme-tx library record)
                                      :scheme/revision (inc (or (:scheme/revision old) 0)))
                         library (assoc :scheme/library [:library/id library]))])))

(defn put-ship! [conn library record]
  (let [id (:ship/id record) old (d/pull @conn '[* {:ship/library [:library/id]}] [:ship/id id])
        library (or (get-in old [:ship/library :library/id]) library)
        parts (paint-refs! conn library (:ship/paint record))]
    (when-not (let [class (d/pull @conn [:db/id :loadout/deleted?] [:loadout/id (:ship/class record)])]
                (and class (or old (not (:loadout/deleted? class)))))
      (throw (ex-info "This ship class is missing." {:type :missing-class})))
    (when-let [scheme (:ship/scheme record)] (ensure-scheme! conn scheme))
    (d/transact! conn (concat (retract-children @conn [:ship/id id] [:ship/paint])
                              (when (:ship/scheme old) [[:db.fn/retractAttribute [:ship/id id] :ship/scheme]])))
    (d/transact! conn [(cond-> {:ship/id id :ship/name (:ship/name record) :ship/deleted? false
                                :ship/revision (inc (or (:ship/revision old) 0))
                                :ship/library [:library/id library] :ship/class [:loadout/id (:ship/class record)]
                                :ship/paint (t/paint-tx library parts id (:ship/paint record))}
                         (:ship/scheme record) (assoc :ship/scheme [:scheme/id (:ship/scheme record)]))])))

(defn put-record! [store library kind record mode]
  (try
    (write! store
            (fn [conn]
              (let [operation (case kind :schemes scheme/put-record :loadouts loadout/put-record :ships ship/put-record)
                    result (operation (records-value @conn kind) record mode)]
                (if (:error result) result
                    (do
                      (when (and (nil? library)
                                 (or (#{:loadouts :ships} kind) (seq (:scheme/layers record))))
                        (throw (ex-info "Select a library before saving part or layer references" {})))
                      ((case kind :schemes put-scheme! :loadouts put-loadout! :ships put-ship!) conn library record)
                      (dissoc result :store))))))
    (catch Exception e {:error :store-write-failed :message (str "Could not save metadata. " (ex-message e))})))

(defn delete-record! [store kind id]
  (try
    (write! store
            (fn [conn]
              (let [result ((case kind :schemes scheme/delete-record :loadouts loadout/delete-record :ships ship/delete-record)
                            (records-value @conn kind) id)
                    [key deleted] (case kind :schemes [:scheme/id :scheme/deleted?] :loadouts [:loadout/id :loadout/deleted?] :ships [:ship/id :ship/deleted?])]
                (if (:error result) result
                    (do (d/transact! conn [{key id deleted true}]) (dissoc result :store))))))
    (catch Exception e {:error :store-write-failed :message (str "Could not delete metadata. " (ex-message e))})))
