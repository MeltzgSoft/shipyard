(ns shipyard.mesh.cache
  "Lazy mesh preprocessing and the content-addressed .symesh disk cache
  (TECHNICAL.md §6.5).

  Everything here is derived. Every entry regenerates from its source STL, so
  eviction can only ever cost recomputation and never loses user data - which is
  why a size cap is safe to enforce bluntly."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [digest]
            [integrant.core :as ig]
            [shipyard.mesh.lod :as lod]
            [shipyard.mesh.stl :as stl]
            [shipyard.system :as system]
            [shipyard.wire :as wire]))

(defn sha256
  "Content hash of a source STL, streamed. Computed only at first preprocess
  (§5.4) - hashing 19 GB at every start is what the scan index exists to avoid."
  [f]
  (digest/sha-256 (fs/file f)))

(defn tier-file [{:keys [dir]} mesh-key tier]
  (fs/file dir (format "%s.%d.symesh" mesh-key tier)))

;; --- eviction ---------------------------------------------------------------

(defn cache-size ^long [{:keys [dir]}]
  (reduce + 0 (map fs/size (filter fs/regular-file? (fs/list-dir dir)))))

(defn evict!
  "Drop least-recently-used tiers until the cache fits under its cap.

  Recency is the file's mtime, touched on every read: `lastAccessTime` is
  unreliable because most filesystems mount `noatime`."
  [{:keys [dir ^long cap-bytes]}]
  (let [files (->> (fs/list-dir dir)
                   (filter fs/regular-file?)
                   (sort-by #(fs/file-time->millis (fs/last-modified-time %))))
        total (reduce + 0 (map fs/size files))]
    (when (> total cap-bytes)
      (loop [[f & more] files, size total, dropped 0]
        (if (or (nil? f) (<= size cap-bytes))
          (log/infof "cache eviction: dropped %d tiers, %,d -> %,d bytes (cap %,d)"
                     dropped total size cap-bytes)
          (let [len (fs/size f)]
            (fs/delete f)
            (recur more (- size len) (inc dropped))))))))

(defn- touch! [f] (fs/set-last-modified-time f (System/currentTimeMillis)))

;; --- preprocessing ----------------------------------------------------------

(defn- preprocess!
  "Parse, weld, generate tiers, encode, write. Runs once per source file ever."
  [{:keys [crease-deg lod-tiers] :as cache} source mesh-key]
  (let [parsed (stl/parse-file source)
        tiers  (lod/generate parsed {:crease-deg crease-deg :tiers lod-tiers})]
    (doseq [[i tier] (map-indexed vector tiers)]
      (let [target (tier-file cache mesh-key i)
            bytes  (wire/encode (assoc tier
                                       :bbox-min (:bbox-min parsed)
                                       :bbox-max (:bbox-max parsed)))]
        (fs/create-dirs (fs/parent target))
        ;; temp file then rename, so a reader never sees a partial tier
        (let [tmp (fs/create-temp-file {:dir (fs/parent target)
                                        :prefix "symesh-" :suffix ".tmp"})]
          (io/copy bytes (fs/file tmp))
          (fs/move tmp target {:replace-existing true}))))
    {:mesh-key mesh-key
     :tiers    (count tiers)
     :tris     (:triangle-count parsed)}))

(defn- run-job
  "The work itself: take the cache hit, or preprocess and then evict."
  [cache source]
  (let [mesh-key (sha256 source)
        t0       (tier-file cache mesh-key 0)]
    (if (fs/regular-file? t0)
      (do (touch! t0) {:mesh-key mesh-key :cached true})
      (let [r (preprocess! cache source mesh-key)]
        (evict! cache)
        r))))

(defn ensure!
  "Return cache metadata for `source`, preprocessing it if needed.

  Two concurrent callers for the same file produce one job, not two. `delay`
  expresses that natively: the first deref runs the body and every other blocks
  on the same result. `swap!` may retry and build a delay it discards, which
  costs nothing precisely because a delay's body does not run until someone
  derefs it - the reason `future` would be wrong here, since a discarded future
  has already started working.

  The work runs on the calling thread, deliberately. There is no executor
  because nothing here needs one: this is a single-user local application that
  views one part at a time, and the batch case - the canary walking the whole
  library - gets bounded parallelism from `pmap` at its own call site, already
  capped at `availableProcessors + 2`.

  Running inline also means an exception propagates as itself. Submitting to a
  pool wrapped every parser error in an `ExecutionException`, so a parser's
  `not a usable STL` ex-info arrived with no readable message.

  If a future UI ever prefetches many distinct parts at once, that is when a
  bounded pool earns its place - preprocessing allocates tens of megabytes per
  part, and the §11 budget is 2 GB peak."
  [{:keys [inflight] :as cache} source]
  (let [k (str (fs/absolutize source))
        d (-> (swap! inflight update k #(or % (delay (run-job cache source))))
              (get k))]
    (try @d (finally (swap! inflight dissoc k)))))

;; --- component --------------------------------------------------------------

(defmethod ig/init-key :shipyard.mesh/cache
  [_ {:keys [crease-deg lod-tiers cap-bytes]}]
  (let [dir (fs/file (system/cache-home) "shipyard" "mesh")]
    (fs/create-dirs dir)
    (log/infof "mesh cache at %s (cap %,d bytes)" (str dir) cap-bytes)
    {:dir dir :crease-deg crease-deg :lod-tiers lod-tiers
     :cap-bytes cap-bytes :inflight (atom {})}))
