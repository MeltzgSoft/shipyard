(ns shipyard.http.jobs
  "Library-scoped mesh preparation and recovery on the common background executor."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.jobs :as workers]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]))

(defn status
  "The recorded state of `part-id`, or nil if no job has ever run for it."
  [{:keys [state]} part-id]
  (get @state part-id))

(defn claim-job
  "Install a candidate status only when the part has no recorded job."
  [statuses part-id candidate]
  (update statuses part-id #(or % candidate)))

(defn forget!
  "Drop a recorded result so the next `submit!` runs the work again. Only a
  failure is ever worth forgetting - a success is a cache hit from then on."
  [{:keys [state]} part-id]
  (swap! state dissoc part-id)
  nil)

(defn clear!
  "Forget every recorded result.

  Used when the library relocates. The table is keyed by part id, and a part id
  is library-relative: after a relocation a recorded `:ready` would hand the
  viewport the previous library's mesh for whatever now sits at that path.
  Jobs already in flight are left to finish - `index/record-mesh-key!` drops
  results that no longer belong to the current library."
  [{:keys [state facet-state]}]
  (reset! state {})
  (reset! facet-state {})
  nil)

(defn- execute! [{:keys [state library cache]} part-id source expected mine]
  (let [library-lock (:state library)
        result (try
                 (let [{:keys [mesh-key tris]} (cache/ensure! cache source)]
                   ;; Recording the key is an optimisation, not part of the
                   ;; result. The mesh is on disk either way and the next run
                   ;; recomputes the key from the source hash, so a part that
                   ;; preprocessed perfectly must never be reported as failed
                   ;; because its derived database entry could not be written.
                   (try
                     (index/record-mesh-key! library part-id mesh-key tris expected)
                     (catch Throwable t
                       (log/warn "could not record the mesh key for" part-id "-"
                                 (ex-message t))))
                   {:state :ready :mesh-key mesh-key})
                 (catch Throwable t
                   (log/warn t "preprocessing failed:" part-id)
                   {:state :failed :message (or (ex-message t) (str (class t)))}))]
    (locking library-lock
      (let [current-source? (index/current-source?! library part-id expected)]
        (swap! state (fn [current]
                       (if (identical? mine (get current part-id))
                         (if current-source?
                           (assoc current part-id result)
                           (dissoc current part-id))
                         current)))))))

(defn submit!
  "Start preprocessing `part-id` unless it is already running, ready or failed.
  Returns the current status map.

  Idempotent under concurrency, and the `swap!` is what makes it so rather than
  the read above it: two Jetty threads can both see nothing recorded, and only
  the one whose own map survives the swap is allowed to submit. Reading the
  result of `swap!` rather than the atom afterwards is what makes that decision
  a single point rather than a race. Library validation and claiming share the
  activation lock, so a delayed caller cannot submit an old root's source."
  [{:keys [scope state library] :as jobs} part-id source]
  (let [library-lock (:state library)]
    (locking library-lock
      (let [expected (assoc (index/part-state! library part-id) :source source)]
        (when (index/current-source?! library part-id expected)
          (let [;; A literal map can be reused by the compiler across calls. Each
                ;; claim needs a distinct identity after clear! and resubmission.
                mine (hash-map :state :running)
                after (swap! state claim-job part-id mine)]
            (when (and (identical? mine (get after part-id))
                       (not (workers/submit! scope #(execute! jobs part-id source expected mine))))
              (swap! state #(if (identical? mine (get % part-id)) (dissoc % part-id) %)))
            ;; A full common queue is retried by the next normal UI poll.
            (or (get @state part-id) {:state :running})))))))

(defn submit-facet-backfill!
  "Run `task` once for a mesh-key-scoped legacy mount recovery.

  The task is intentionally supplied by the HTTP layer: it owns the catalog
  write, while this component owns bounded background execution and duplicate
  suppression."
  [{:keys [scope facet-state]} key task]
  (loop []
    (let [before @facet-state]
      (if-let [existing (get before key)]
        existing
        (let [running (hash-map :state :running)]
          (if (compare-and-set! facet-state before (assoc before key running))
            (do
              (when-not (workers/submit! scope
                                         #(let [result (try
                                                         (task)
                                                         {:state :complete}
                                                         (catch Throwable t
                                                           (log/warn t "mount facet recovery failed:" (first key))
                                                           {:state :failed
                                                            :message (or (ex-message t) (str (class t)))}))]
                                            (swap! facet-state (fn [current] (if (identical? running (get current key))
                                                                               (assoc current key result) current)))))
                (swap! facet-state #(if (identical? running (get % key)) (dissoc % key) %)))
              running)
            (recur)))))))

;; --- component --------------------------------------------------------------

(defmethod ig/init-key :shipyard.http/jobs [_ {:keys [library cache workers]}]
  {:workers workers :scope (workers/scope! workers)
   :state (atom {}) :facet-state (atom {}) :library library :cache cache})

(defmethod ig/halt-key! :shipyard.http/jobs [_ {:keys [scope]}]
  (workers/close! scope))
