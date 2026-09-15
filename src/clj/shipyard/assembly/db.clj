(ns shipyard.assembly.db
  "Integrant-owned ephemeral draft coordination and mesh preparation."
  (:require [babashka.fs :as fs]
            [integrant.core :as ig]
            [shipyard.assembly.transforms :as transforms]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout.operations :as loadouts]
            [shipyard.http.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]))

(defmethod ig/init-key :shipyard.assembly/db [_ _]
  {:state (atom {:draft transforms/empty-draft :sequence 0 :root nil :scene {}})})

(defn- fresh-sources [library part-ids]
  (into {}
        (keep (fn [part-id]
                (when-let [source (index/fresh-source-file! library part-id)]
                  [part-id source])))
        part-ids))

(defn- active-part-ids [draft operation]
  (cond-> (set (vals (:assignments draft)))
    (:hull draft) (conj (:hull draft))
    (:part-id operation) (conj (:part-id operation))))

(defn- prepare! [{:keys [jobs library cache]} sources part-ids retry]
  (into {}
        (for [id (distinct part-ids)]
          (let [source (get sources id)
                _ (when (= retry id) (jobs/forget! jobs id))
                key (index/mesh-key! library id)]
            [id (cond
                  (nil? source) {:state :failed :message "The source mesh is unavailable."}
                  (and key (fs/regular-file? (cache/tier-file cache key 0))) {:state :ready :mesh-key key}
                  :else (jobs/submit! jobs id source))]))))

(defn request!
  "Serialize draft changes and responses. Polling advances event sequence, never draft revision."
  [{:keys [catalog library] {state :state} :assembly :as deps} operation {:keys [resume? retry]}]
  (locking state
    (let [{:keys [draft sequence root scene]} @state
          draft-before draft
          database (catalog/snapshot! catalog)
          current-root (index/root! library)
          cached-sources (index/source-files! library)
          active-sources (fresh-sources library (active-part-ids draft operation))
          available (reduce disj (set (keys cached-sources))
                            (remove #(contains? active-sources %)
                                    (active-part-ids draft operation)))
          changed-root? (and (:hull draft) (not= root current-root))
          result (cond
                   (and changed-root? (not (#{:reset :hull} (:op operation))))
                   {:draft draft :error :library-changed :status 409}
                   operation (transforms/transition database draft operation available)
                   :else {:draft draft :status 200})
          draft (:draft result)
          blocked-root? (= :library-changed (:error result))
          placement-result (try {:scene (if blocked-root? {} (transforms/placements database draft))}
                                (catch clojure.lang.ExceptionInfo e
                                  {:scene {} :error (:code (ex-data e))}))
          after (if (:error placement-result) scene (:scene placement-result))
          effective-draft (if (:error placement-result) draft-before draft)
          sources (fresh-sources library (map :part-id (vals after)))
          prepared (prepare! deps sources (map :part-id (vals after)) retry)
          mesh-keys (into {} (keep (fn [[id status]] (when (= :ready (:state status)) [id (:mesh-key status)]))) prepared)
          reset? (or resume? (and operation (not (:error result)) (#{:hull :reset} (:op operation))))
          envelope {:revision (:revision draft) :sequence (inc sequence)
                    :commands (transforms/commands scene after mesh-keys reset?)
                    :mount-markers (transforms/mount-markers database effective-draft)}]
      (reset! state {:draft effective-draft :sequence (inc sequence)
                     :root (if blocked-root? root current-root) :scene after})
      (merge result {:database database :prepared prepared :event envelope
                     :available available}
             (when (:error placement-result) {:error (:error placement-result) :status 422})))))

(defn- loadout-available [library]
  (->> (index/source-files! library) keys
       (filter #(index/fresh-source-file! library %)) set))

(defn save-loadout! [{:keys [catalog library loadout-store] {state :state} :assembly :as deps} name]
  (locking state
    (let [result (loadouts/save! loadout-store (catalog/snapshot! catalog) (:draft @state)
                                 (loadout-available library) name)]
      (assoc (request! deps nil {}) :error (:error result)))))

(defn load-loadout! [{:keys [catalog library loadout-store] {state :state} :assembly :as deps} id]
  (locking state
    (let [result (loadouts/load! loadout-store (catalog/snapshot! catalog) (:draft @state)
                                 (loadout-available library) id)]
      (if (:error result)
        (assoc (request! deps nil {}) :error (:error result) :status 422)
        (do (swap! state assoc :draft (:draft result))
            (request! deps nil {:resume? true}))))))

(defn duplicate-loadout! [{:keys [catalog library loadout-store] :as deps} id name]
  (let [result (loadouts/duplicate! loadout-store (catalog/snapshot! catalog)
                                    (loadout-available library) id name)]
    (assoc (request! deps nil {}) :error (:error result))))
