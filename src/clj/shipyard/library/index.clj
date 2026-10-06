(ns shipyard.library.index
  "Library root plus the mtime+size scan cache (TECHNICAL.md §5.4).

  Two keys, two purposes. `:part/id` is the folder path, free during the walk.
  `:part/mesh-key` is the SHA-256 of the source STL and is computed only when a
  part is first preprocessed - hashing 19 GB at every start is exactly what this
  namespace exists to prevent."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.library.scan :as scan]
            [shipyard.settings.db :as settings]
            [shipyard.store.scan-index :as scan-index]))

(defn- stat! [f] {:mtime (fs/file-time->millis (fs/last-modified-time f)) :size (fs/size f)})

(defn fresh?
  "Whether cached metadata matches an already-inspected source."
  [entry source-stat]
  (and entry (= (select-keys entry [:mtime :size]) source-stat)))

(defn fresh-source?!
  "Inspect a source file and compare it with a cached entry."
  [entry source]
  (and source (fresh? entry (stat! source))))

(defn name-of [variant]
  (case variant
    :unsupported-pitted "unsupported-pitted.stl"
    :unsupported        "unsupported.stl"
    :supported          "supported.stl"))

(defn refresh
  "Merge scan results with stored entries using precomputed source metadata."
  [parts stored source-stats]
  (reduce
   (fn [acc {:part/keys [id source]}]
     (if-not source
       (assoc acc id {})   ; no source can justify retaining derived metadata
       (let [source-stat (get source-stats id)
             old         (get stored id)]
         (assoc acc id
                (merge source-stat
                       (when (fresh? old source-stat)
                         (select-keys old [:mesh-key :tris :escort-analysis])))))))
   {}
   parts))

(defn refresh!
  "Inspect source files, then merge the scan with the stored index."
  [parts root stored]
  (let [source-stats (into {}
                           (keep (fn [{:part/keys [id source]}]
                                   (when source
                                     [id (stat! (fs/file root id (name-of source)))])))
                           parts)]
    (refresh parts stored source-stats)))

(defn current-source?!
  "Whether a captured preprocess context still identifies this library's source.
  Callers coordinating publication hold the library state lock around this check."
  [{:keys [state]} part-id expected]
  (let [{:keys [root entries source-files]} @state
        entry (get entries part-id)]
    (and entry
         (= root (:root expected))
         (= (select-keys entry [:mtime :size])
            (select-keys (:entry expected) [:mtime :size]))
         (= (get source-files part-id) (:source expected))
         (try (fresh-source?! entry (:source expected))
              (catch Exception _ false)))))

(defn record-mesh-key!
  "Remember a preprocess result for a part still present in the active library.
  Persist only that entry, under the shared database transaction boundary.
  Jobs supply their submission context so late results cannot cross libraries
  or overwrite a changed source's metadata."
  ([library part-id mesh-key tris]
   (record-mesh-key! library part-id mesh-key tris nil))
  ([{:keys [state store] :as library} part-id mesh-key tris expected]
   (locking state
     (let [{:keys [root entries] :as before} @state
           entry (get entries part-id)]
       (when (and entry
                  (or (nil? expected)
                      (current-source?! library part-id expected)))
         (let [entry (cond-> (assoc entry :mesh-key mesh-key)
                       tris (assoc :tris tris))]
           (scan-index/put-entry! store root part-id entry)
           (reset! state (assoc-in before [:entries part-id] entry))))))
   mesh-key))

(defn mesh-key!
  "The recorded mesh key for a part, or nil if it has never been preprocessed
  or its source file has changed since."
  [{:keys [state]} part-id]
  (get-in @state [:entries part-id :mesh-key]))

(defn part-state!
  "The current root and scan-index entry for `part-id`, read from one atom
  snapshot. Selection handlers need this coherence: a settings change can swap
  roots while a request is in flight."
  [{:keys [state]} part-id]
  (let [{:keys [root entries]} @state]
    {:root root :entry (get entries part-id)}))

(defn escort-analysis!
  "Cached escort geometry analysis for a part, if its source file is unchanged."
  [{:keys [state]} part-id]
  (get-in @state [:entries part-id :escort-analysis]))

(defn with-escort-analysis
  "Return `state` with analysis cached when `part-id` still exists."
  [state part-id analysis]
  (if (contains? (:entries state) part-id)
    (assoc-in state [:entries part-id :escort-analysis] analysis)
    state))

(defn record-escort-analysis!
  "Persist on-demand escort analysis in this part's derived scan entry.
  Startup still does not open STL geometry."
  [{:keys [state store]} part-id analysis]
  (locking state
    (let [before @state
          updated (with-escort-analysis before part-id analysis)]
      (when (contains? (:entries updated) part-id)
        (scan-index/put-entry! store (:root updated) part-id (get-in updated [:entries part-id]))
        (reset! state updated))))
  analysis)

;; --- the component, and the root it can be pointed at ------------------------

(defn root!
  "Where the library currently is, or nil when nobody has said yet."
  [{:keys [state]}] (:root @state))

(defn available?!
  "Whether the root is a directory that exists **now**. False is the normal
  state of a fresh install, not an error.

  Checked rather than remembered, and the difference is a stat per library
  request. A drive can be unmounted, or a folder renamed, under a running
  server; a remembered answer keeps listing that library's parts, and every row
  in the list 404s when clicked. Reporting the missing folder is both true and
  the only thing the user can act on."
  [{:keys [state]}]
  (let [{:keys [root]} @state]
    (boolean (and root (fs/directory? (fs/file root))))))

(defn parts! [{:keys [state]}] (:parts @state))

(defn with-variant [state part-id variant]
  (if (some #(= part-id (:part/id %)) (:parts state))
    (update state :parts
            (fn [parts]
              (mapv (fn [part]
                      (if (= part-id (:part/id part))
                        (update part :part/variants #(conj (set %) variant))
                        part)) parts)))
    state))

(defn record-variant!
  "Publish an observed output in the current inventory, retaining source caches."
  [{:keys [state]} part-id variant]
  (locking state (swap! state with-variant part-id variant)))

(defn source-files!
  "The renderable source files discovered by the most recent library scan.

  This is deliberately scan-derived rather than rebuilt by request handlers:
  walking a full library to rediscover files turns a cheap UI poll into
  thousands of filesystem calls. Callers that will open a particular file
  should additionally use `fresh-source-file!` below."
  [{:keys [state]}]
  (:source-files @state))

(defn fresh-source-file!
  "The scanned source for `part-id` when it still matches its indexed stamp.

  Assembly requests check only their active parts this way. A missing or
  changed file must not be rendered from a stale mesh key, but checking every
  catalog entry on every poll is unnecessary work."
  [{:keys [state]} part-id]
  (let [{:keys [entries source-files]} @state
        source (get source-files part-id)]
    (when (try
            (fresh-source?! (get entries part-id) source)
            (catch Exception _ false))
      source)))

(defn- source-files [root parts]
  (into {}
        (keep (fn [{:part/keys [id source renderable]}]
                (when (and renderable source)
                  [id (fs/file root id (name-of source))])))
        parts))

(defn- scan-state!
  "Scan `root`, refresh its derived database entries, and build the complete
  candidate snapshot shared by startup and relocation."
  [root store]
  (if (str/blank? (str root))
    ;; Nothing set. Not a failure - the settings form exists for exactly this
    ;; state, and there is nothing to scan or stamp until it is used.
    {:root nil :parts [] :entries {} :source-files {}}
    (let [dir (fs/file root)]
      (when-not (fs/directory? dir)
        ;; Not fatal: the app must still start so the user can point it
        ;; somewhere real. A hard failure here makes a fresh install unusable.
        (log/warn "library root does not exist:" root))
      (let [stored (scan-index/entries! store root)
            parts  (or (scan/scan! dir) [])
            idx    (refresh! parts root stored)]
        (log/infof "library: %d parts, %d with a cached mesh key"
                   (count parts) (count (filter :mesh-key (vals idx))))
        (when-not (= idx stored) (scan-index/replace! store root idx))
        {:root (str root)
         :parts parts
         :entries idx
         :source-files (source-files root parts)}))))

(defn prepare-root!
  "Scan a candidate root without publishing it as the active library."
  [{:keys [store]} root]
  (scan-state! root store))

(defn set-root!
  "Point the library at `root` and rescan, in place.

  In place, rather than by rebuilding the component, because the route table
  closes over its dependencies at build time (`shipyard.http.routes/routes`).
  Swapping this atom is what lets a running server serve a different library
  without a restart; rebuilding the component would leave every handler holding
  the old one."
  [{:keys [store state]} root]
  (locking state
    (reset! state (scan-state! root store))))

(defmethod ig/init-key :shipyard.library/index [_ {:keys [root store config-dir resolve-settings?] :or {resolve-settings? true}}]
  (when-not store
    (throw (ex-info "Library index requires the shared application store" {})))
  (let [root (if resolve-settings? (settings/initial-root! store root config-dir) root)]
    {:store store :state (atom (scan-state! root store))}))
