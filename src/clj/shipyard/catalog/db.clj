(ns shipyard.catalog.db
  "In-memory catalog over datascript (TECHNICAL.md §1.2, §4).

  **Datascript ingests at startup; it never owns data.** It is in-memory and
  non-durable, so it can only be a derived index. The durable layer is per-part
  `shipyard.edn` sidecars plus the user data files.

  Writes are write-through, file first: if a transact throws, the data is
  already on disk and the next restart picks it up. The reverse order can lose
  a write."
  (:require [clojure.tools.logging :as log]
            [datascript.core :as d]
            [integrant.core :as ig]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.library.index :as index]))

(def schema
  {:part/id          {:db/unique :db.unique/identity}
   :part/bundle      {:db/index true}
   :part/class       {:db/index true}
   :part/role-hint   {:db/index true}     ; browsing only - never compatibility (§5.2)
   :part/role-source {}
   :part/weapons?    {:db/index true}     ; directory facts, not guesses
   :part/turrets?    {:db/index true}
   ;; Indexed because the mount wizard's first question is "what still needs
   ;; turret pits authored" - a shortlist of 180, superseded per part by a real
   ;; socket with :mount/accepts #{:turret} (SPEC §5.4).
   :part/accepts-turrets? {:db/index true}
   :part/name        {}
   :part/variants    {:db/cardinality :db.cardinality/many}
   :part/source      {}
   :part/renderable  {:db/index true}
   :part/mesh-key    {}
   :part/tris        {}
   :part/mounts      {:db/cardinality :db.cardinality/many
                      :db/valueType   :db.type/ref
                      :db/isComponent true}

   :mount/id         {}
   :mount/kind       {:db/index true}
   :mount/accepts    {:db/cardinality :db.cardinality/many}
   :mount/capacity   {}
   :mount/pos        {} :mount/axis {} :mount/roll {}
   :mount/magnet     {}
   :mount/origin     {}

   :loadout/id       {:db/unique :db.unique/identity}
   :loadout/hull     {:db/valueType :db.type/ref}
   :loadout/slots    {:db/cardinality :db.cardinality/many
                      :db/valueType   :db.type/ref
                      :db/isComponent true}
   :slot/mount-id    {} :slot/part {:db/valueType :db.type/ref}

   :fleet/id         {:db/unique :db.unique/identity}
   :fleet/loadouts   {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :scheme/id        {:db/unique :db.unique/identity}})

(def geometry-keys
  "Attributes that must never exist. The catalog holds metadata only; a vertex
  buffer in here would balloon the heap and make the DB non-derivable."
  #{:part/positions :part/normals :part/indices :part/vertices :part/geometry})

(defn- apply-sidecar
  "Manual sidecar facts beat scan inference; scan facts remain only hints."
  [part sidecar]
  (if-let [role (:part/role sidecar)]
    (assoc part :part/role-hint role :part/role-source :manual)
    part))

(defn part->tx
  "Part record plus its sidecar data -> a transaction map."
  [part sidecar]
  (cond-> (into {} (remove (comp nil? val)) (select-keys part
                                                         [:part/id :part/bundle :part/class :part/name
                                                          :part/role-hint :part/role-source :part/source
                                                          :part/renderable :part/mesh-key :part/tris
                                                          :part/weapons? :part/turrets?
                                                          :part/accepts-turrets?]))
    (seq (:part/variants part)) (assoc :part/variants (vec (:part/variants part)))
    (seq (:mounts sidecar))     (assoc :part/mounts (vec (:mounts sidecar)))))

(defn ingest
  "Build a fresh DB from scanned parts, reading each part's sidecar.

  A malformed sidecar is logged and skipped rather than aborting the whole
  library - one bad file must not make every other part invisible."
  [parts root]
  (let [conn (d/create-conn schema)
        tx   (reduce (fn [acc part]
                       (let [sc (try
                                  (sidecar/read-sidecar root (:part/id part))
                                  (catch Exception e
                                    (log/warn (ex-message e))
                                    nil))]
                         (conj acc (part->tx (apply-sidecar part sc) sc))))
                     [] parts)]
    (d/transact! conn tx)
    conn))

;; --- queries ----------------------------------------------------------------

(defn conn
  "The live connection. `snapshot` is what a handler wants; this is for the
  writers, and for tests asserting on transactions."
  [{:keys [state]}]
  (:conn @state))

(defn snapshot
  "The current value of the catalog. A query takes a db value, not a connection,
  so a handler that reads several facets sees one consistent index.

  One deref, not two: a relocation replaces the whole state map, so reading it
  once is what stops a handler pairing one library's connection with another's
  root."
  [{:keys [state]}]
  (d/db (:conn @state)))

(defn bundles [db]
  (sort (d/q '[:find [?b ...] :where [_ :part/bundle ?b]] db)))

(defn classes
  "Hull classes, across the library or within one bundle."
  ([db] (sort (d/q '[:find [?c ...] :where [_ :part/class ?c]] db)))
  ([db bundle]
   (sort (d/q '[:find [?c ...] :in $ ?b
                :where [?e :part/bundle ?b] [?e :part/class ?c]] db bundle))))

(defn roles
  "Role hints present in the library. Derived rather than listed: the inference
  rules in §5.2 grow, and a hard-coded menu would quietly stop matching them."
  [db]
  (sort-by name (d/q '[:find [?r ...] :where [_ :part/role-hint ?r]] db)))

(defn browse
  "Filter the library. Every criterion is optional; `q` matches the part name
  case-insensitively, and `accepts-turrets?` narrows to the parts the mount
  wizard still has turret pits to author on."
  [db {:keys [bundle class role q accepts-turrets?]}]
  (->> (d/q '[:find [(pull ?e [*]) ...] :where [?e :part/id]] db)
       (filter #(or (nil? bundle) (= bundle (:part/bundle %))))
       (filter #(or (nil? class)  (= class (:part/class %))))
       (filter #(or (nil? role)   (= role (:part/role-hint %))))
       (filter #(or (nil? accepts-turrets?)
                    (= (boolean accepts-turrets?) (boolean (:part/accepts-turrets? %)))))
       (filter #(or (nil? q)
                    (re-find (re-pattern (str "(?i)" (java.util.regex.Pattern/quote q)))
                             (str (:part/name %)))))
       (sort-by :part/id)))

(defn part [db id]
  (d/pull db '[*] [:part/id id]))

;; --- write-through ----------------------------------------------------------

(defn- retract-current-mounts [db part-id]
  (mapv (fn [eid] [:db.fn/retractEntity eid])
        (d/q '[:find [?m ...]
               :in $ ?id
               :where [?p :part/id ?id]
               [?p :part/mounts ?m]]
             db part-id)))

(defn save-mounts!
  "Persist a part's mounts. **File first**, then index: if the transact throws,
  the data is already safe on disk and the next restart picks it up."
  [{:keys [state]} part-id mounts]
  (let [{:keys [conn root]} @state]
    (sidecar/update-sidecar! root part-id assoc :mounts mounts)
    (d/transact! conn (concat (retract-current-mounts @conn part-id)
                              [{:part/id part-id :part/mounts (vec mounts)}]))
    mounts))

(defn save-authoring!
  "Persist mounts and an optional manual role override, file first."
  [{:keys [state]} part-id {:keys [mounts part-role]}]
  (let [{:keys [conn root]} @state
        update-sidecar (fn [data]
                         (cond-> (assoc data :mounts (vec mounts))
                           part-role (assoc :part/role part-role)))
        part-tx (cond-> {:part/id part-id :part/mounts (vec mounts)}
                  part-role (assoc :part/role-hint part-role
                                   :part/role-source :manual))]
    (sidecar/update-sidecar! root part-id update-sidecar)
    (d/transact! conn (concat (retract-current-mounts @conn part-id)
                              [part-tx]))
    {:mounts mounts :part-role part-role}))

;; --- component --------------------------------------------------------------

(defn reingest!
  "Rebuild the catalog from `parts` scanned under `root`, in place.

  Datascript is a derived index and never the durable layer, so there is
  nothing to migrate here - the cheapest correct answer to \"the library
  moved\" is a new connection. In place rather than a new component because the
  route table closes over its dependencies; see
  `shipyard.library.index/set-root!`."
  [{:keys [state]} parts root]
  (let [parts (or parts [])]
    (log/infof "catalog: %d parts re-ingested" (count parts))
    (reset! state {:conn (ingest parts root) :root root})))

(defmethod ig/init-key :shipyard.catalog/db [_ {:keys [library]}]
  (let [parts (index/parts library)
        root  (index/root library)]
    (log/infof "catalog: %d parts ingested" (count parts))
    {:state (atom {:conn (ingest (or parts []) root) :root root})}))
