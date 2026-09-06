(ns shipyard.http.jobs
  "Preprocessing off the request thread (TECHNICAL.md §6.5, §7).

  `shipyard.mesh.cache/ensure!` runs on the calling thread deliberately: the
  canary wants exactly that back-pressure, and an inline exception arrives as
  itself rather than wrapped in an `ExecutionException`. A web request wants the
  opposite - it must return in milliseconds while a cold Cruiser hull takes
  seconds. §6.5 said a bounded pool would earn its place once a UI existed
  prefetching distinct parts; this is that pool, and it is here rather than in
  the cache so the cache keeps its inline contract for every other caller.

  Two threads, not more. Preprocessing allocates tens of megabytes per part
  against a 2 GB peak budget (§11), and this is a single-user application
  looking at one part at a time."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache])
  (:import [java.util.concurrent ExecutorService Executors ThreadFactory]))

(def ^:const threads 2)

(defn- daemon-factory []
  (let [n (atom 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r (str "shipyard-preprocess-" (swap! n inc)))
          ;; Daemon: a job in flight must never keep the JVM alive after the
          ;; server has stopped.
          (.setDaemon true))))))

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
  [{:keys [state]}]
  (reset! state {})
  nil)

(defn- execute! [{:keys [state library cache]} part-id source]
  (let [result (try
                 (let [{:keys [mesh-key tris]} (cache/ensure! cache source)]
                   ;; Recording the key is an optimisation, not part of the
                   ;; result. The mesh is on disk either way and the next run
                   ;; recomputes the key from the source hash, so a part that
                   ;; preprocessed perfectly must never be reported as failed
                   ;; because an index file could not be written.
                   (try
                     (index/record-mesh-key! library part-id mesh-key tris)
                     (catch Throwable t
                       (log/warn "could not record the mesh key for" part-id "-"
                                 (ex-message t))))
                   {:state :ready :mesh-key mesh-key})
                 (catch Throwable t
                   (log/warn t "preprocessing failed:" part-id)
                   {:state :failed :message (or (ex-message t) (str (class t)))}))]
    (swap! state assoc part-id result)))

(defn submit!
  "Start preprocessing `part-id` unless it is already running, ready or failed.
  Returns the current status map.

  Idempotent under concurrency, and the `swap!` is what makes it so rather than
  the read above it: two Jetty threads can both see nothing recorded, and only
  the one whose own map survives the swap is allowed to submit. Reading the
  result of `swap!` rather than the atom afterwards is what makes that decision
  a single point rather than a race."
  [{:keys [^ExecutorService pool state] :as jobs} part-id source]
  (let [mine  {:state :running}
        after (swap! state claim-job part-id mine)]
    (when (identical? mine (get after part-id))
      (.submit pool ^Runnable #(execute! jobs part-id source)))
    (get after part-id)))

;; --- component --------------------------------------------------------------

(defmethod ig/init-key :shipyard.http/jobs [_ {:keys [library cache]}]
  {:pool    (Executors/newFixedThreadPool threads (daemon-factory))
   :state   (atom {})
   :library library
   :cache   cache})

(defmethod ig/halt-key! :shipyard.http/jobs [_ {:keys [^ExecutorService pool]}]
  (when pool (.shutdownNow pool)))
