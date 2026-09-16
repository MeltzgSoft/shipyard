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
            [shipyard.wire :as wire])
  (:import [java.nio.file CopyOption Files NoSuchFileException StandardCopyOption]))

(defn sha256!
  "Content hash of a source STL, streamed. Computed only at first preprocess
  (§5.4) - hashing 19 GB at every start is what the scan index exists to avoid."
  [f]
  (digest/sha-256 (fs/file f)))

(defn tier-file [{:keys [dir]} mesh-key tier]
  (fs/file dir (format "%s.%d.symesh" mesh-key tier)))

;; --- eviction ---------------------------------------------------------------

(defn- cache-tier-file?
  "A completed cache tier, as opposed to a writer's temporary file."
  [f]
  (boolean (re-matches #"[0-9a-f]{64}\.\d+\.symesh" (str (fs/file-name f)))))

(defn- cache-tier-files [dir]
  (filter (every-pred fs/regular-file? cache-tier-file?) (fs/list-dir dir)))

(defn- tier-info! [file]
  (try
    {:file file :size (fs/size file)
     :mtime (fs/file-time->millis (fs/last-modified-time file))}
    ;; Another process may clear derived files even while our own lock is held.
    (catch NoSuchFileException _ nil)))

(defn cache-size! ^long [{:keys [dir files-lock]}]
  (locking files-lock
    (reduce + 0 (keep (comp :size tier-info!) (cache-tier-files dir)))))

(defn eviction-plan
  "Choose oldest cache entries until their remaining size fits the cap."
  [files cap-bytes]
  (let [ordered (sort-by :mtime files)
        total   (reduce + 0 (map :size ordered))]
    (loop [[file & more] ordered
           size total
           drop []]
      (if (or (nil? file) (<= size cap-bytes))
        {:files drop :before total :after size}
        (recur more (- size (:size file)) (conj drop (:file file)))))))

(defn evict!
  "Drop least-recently-used tiers until the cache fits under its cap.

  Recency is the file's mtime, touched on every read: `lastAccessTime` is
  unreliable because most filesystems mount `noatime`."
  [{:keys [dir files-lock ^long cap-bytes]}]
  (locking files-lock
    (let [entries (keep tier-info! (cache-tier-files dir))
          {:keys [files before after]} (eviction-plan entries cap-bytes)]
      (when (seq files)
        (doseq [f files] (Files/deleteIfExists (fs/path f)))
        (log/infof "cache eviction: dropped %d tiers, %,d -> %,d bytes (cap %,d)"
                   (count files) before after cap-bytes)))))

(defn- touch! [f] (fs/set-last-modified-time f (System/currentTimeMillis)))

;; --- preprocessing ----------------------------------------------------------

(defn- preprocess!
  "Prepare in isolation, then publish complete tiers and evict under one lock."
  [{:keys [dir files-lock crease-deg lod-tiers] :as cache} source mesh-key]
  (let [parsed (stl/parse-file! source)
        tiers (lod/generate parsed {:crease-deg crease-deg :tiers lod-tiers})
        staging (fs/create-temp-dir {:dir dir :prefix "symesh-"})]
    (try
      (let [prepared
            (mapv (fn [[i tier]]
                    (let [tmp (fs/path staging (str i ".tmp"))]
                      (io/copy (wire/encode (assoc tier
                                                   :bbox-min (:bbox-min parsed)
                                                   :bbox-max (:bbox-max parsed)))
                               (fs/file tmp))
                      [tmp (fs/path (tier-file cache mesh-key i))]))
                  (map-indexed vector tiers))]
        (locking files-lock
          ;; Tier zero is the readiness marker, so publish it last. Unsupported
          ;; atomic moves fail explicitly; never expose a partially copied tier.
          (doseq [[tmp target] (reverse prepared)]
            (Files/move tmp target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))
          (evict! cache)))
      {:mesh-key mesh-key :tiers (count tiers) :tris (:triangle-count parsed)}
      (finally (fs/delete-tree staging)))))

(defn- run-job!
  "The work itself: take the cache hit, or preprocess and then evict."
  [{:keys [files-lock] :as cache} source mesh-key]
  (let [t0 (tier-file cache mesh-key 0)
        cached? (locking files-lock
                  (try
                    (when (fs/regular-file? t0) (touch! t0) true)
                    (catch NoSuchFileException _ false)))]
    (if cached?
      {:mesh-key mesh-key :cached true}
      (preprocess! cache source mesh-key))))

(defn ensure!
  "Return cache metadata, sharing inline work by content hash across source paths.

  Hashing precedes admission. Every caller for the same mesh awaits one delay;
  distinct meshes can still parse and encode concurrently. Only publication,
  cache metadata reads and eviction share the component's filesystem lock."
  [{:keys [inflight] :as cache} source]
  (let [mesh-key (sha256! source)
        candidate (delay (run-job! cache source mesh-key))
        job (get (swap! inflight update mesh-key #(or % candidate)) mesh-key)]
    (try
      @job
      (finally
        ;; A waiter for a completed job must not remove a newer retry's claim.
        (swap! inflight #(if (identical? job (get % mesh-key))
                           (dissoc % mesh-key) %))))))

;; --- component --------------------------------------------------------------

(defmethod ig/init-key :shipyard.mesh/cache
  [_ {:keys [crease-deg lod-tiers cap-bytes cache-home
             facet-angle-deg facet-plane-epsilon-mm]}]
  ;; `cache-home` is injectable for the same reason the scan index's is: an
  ;; E2E run must not evict the developer's real cache to prove a point.
  (let [dir (fs/file (or cache-home (system/cache-home!)) "shipyard" "mesh")]
    (fs/create-dirs dir)
    (log/infof "mesh cache at %s (cap %,d bytes)" (str dir) cap-bytes)
    {:dir dir :crease-deg crease-deg :lod-tiers lod-tiers
     :facet-angle-deg facet-angle-deg
     :facet-plane-epsilon-mm facet-plane-epsilon-mm
     :cap-bytes cap-bytes :inflight (atom {}) :files-lock (Object.)}))
