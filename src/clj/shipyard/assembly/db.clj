(ns shipyard.assembly.db
  "Integrant-owned ephemeral draft coordination and mesh preparation."
  (:require [babashka.fs :as fs]
            [integrant.core :as ig]
            [shipyard.assembly.transforms :as transforms]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]))

(defmethod ig/init-key :shipyard.assembly/db [_ _]
  {:state (atom {:draft transforms/empty-draft :sequence 0 :root nil :scene {}})})

(defn- sources! [database library]
  (let [root (index/root! library)]
    (into {} (for [part (catalog/browse database {})
                   :when (and root (:part/renderable part) (:part/source part))
                   :let [file (fs/file root (:part/id part) (index/name-of (:part/source part)))]
                   :when (and (fs/regular-file? file)
                              (index/fresh-source?! (:entry (index/part-state! library (:part/id part))) file))]
               [(:part/id part) file]))))

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
          database (catalog/snapshot! catalog)
          current-root (index/root! library)
          sources (sources! database library)
          changed-root? (and (:hull draft) (not= root current-root))
          result (cond
                   (and changed-root? (not (#{:reset :hull} (:op operation))))
                   {:draft draft :error :library-changed :status 409}
                   operation (transforms/transition database draft operation (set (keys sources)))
                   :else {:draft draft :status 200})
          draft (:draft result)
          blocked-root? (= :library-changed (:error result))
          placement-result (try {:scene (if blocked-root? {} (transforms/placements database draft))}
                                (catch clojure.lang.ExceptionInfo e
                                  {:scene {} :error (:code (ex-data e))}))
          after (:scene placement-result)
          prepared (prepare! deps sources (map :part-id (vals after)) retry)
          mesh-keys (into {} (keep (fn [[id status]] (when (= :ready (:state status)) [id (:mesh-key status)]))) prepared)
          reset? (or resume? (and operation (not (:error result)) (#{:hull :reset} (:op operation))))
          envelope {:revision (:revision draft) :sequence (inc sequence)
                    :commands (transforms/commands scene after mesh-keys reset?)}]
      (reset! state {:draft draft :sequence (inc sequence)
                     :root (if blocked-root? root current-root) :scene after})
      (merge result {:database database :prepared prepared :event envelope
                     :available (set (keys sources))}
             (when (:error placement-result) {:error (:error placement-result) :status 422})))))
