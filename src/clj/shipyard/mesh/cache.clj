(ns shipyard.mesh.cache
  "Lazy mesh preprocessing and the content-addressed .symesh disk cache
  (TECHNICAL.md §6.5).

  Everything here is derived. Every entry regenerates from its source STL, so
  eviction can only ever cost recomputation and never loses user data - which is
  why a size cap is safe to enforce bluntly."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.mesh.lod :as lod]
            [shipyard.mesh.stl :as stl]
            [shipyard.system :as system]
            [shipyard.wire :as wire])
  (:import [java.io File]
           [java.security MessageDigest]
           [java.util.concurrent CompletableFuture ConcurrentHashMap ExecutorService Executors]))

(defn sha256
  "Content hash of a source STL. Computed only at first preprocess (§5.4)."
  [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream f)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n) (.update md buf 0 n) (recur)))))
    (->> (.digest md) (map #(format "%02x" %)) (apply str))))

(defn tier-file ^File [{:keys [^File dir]} mesh-key tier]
  (io/file dir (format "%s.%d.symesh" mesh-key tier)))

(defn- resolve-threads [threads]
  (if (= :auto threads)
    ;; CPU-bound native and array work: a fixed pool, never virtual threads.
    (.availableProcessors (Runtime/getRuntime))
    threads))

;; --- eviction ---------------------------------------------------------------

(defn cache-size ^long [{:keys [^File dir]}]
  (reduce + 0 (map #(.length ^File %) (filter #(.isFile ^File %) (file-seq dir)))))

(defn evict!
  "Drop least-recently-used tiers until the cache fits under its cap.

  Recency is the file's mtime, touched on every read: `lastAccessTime` is
  unreliable because most filesystems mount `noatime`."
  [{:keys [^File dir ^long cap-bytes]}]
  (let [files (->> (file-seq dir) (filter #(.isFile ^File %)) (sort-by #(.lastModified ^File %)))
        total (reduce + 0 (map #(.length ^File %) files))]
    (when (> total cap-bytes)
      (loop [[^File f & more] files, size total, dropped 0]
        (if (or (nil? f) (<= size cap-bytes))
          (log/infof "cache eviction: dropped %d tiers, %,d -> %,d bytes (cap %,d)"
                     dropped total size cap-bytes)
          (let [len (.length f)]
            (.delete f)
            (recur more (- size len) (inc dropped))))))))

(defn- touch! [^File f] (.setLastModified f (System/currentTimeMillis)))

;; --- preprocessing ----------------------------------------------------------

(defn- preprocess!
  "Parse, weld, generate tiers, encode, write. Runs once per source file ever."
  [{:keys [crease-deg lod-tiers] :as cache} ^File source mesh-key]
  (let [parsed (stl/parse-file source)
        tiers  (lod/generate parsed {:crease-deg crease-deg :tiers lod-tiers})]
    (doseq [[i tier] (map-indexed vector tiers)]
      (let [target (tier-file cache mesh-key i)
            bytes  (wire/encode (assoc tier
                                       :bbox-min (:bbox-min parsed)
                                       :bbox-max (:bbox-max parsed)))]
        (io/make-parents target)
        (let [tmp (File/createTempFile "symesh-" ".tmp" (.getParentFile target))]
          (with-open [o (io/output-stream tmp)] (.write o ^bytes bytes))
          (java.nio.file.Files/move
           (.toPath tmp) (.toPath target)
           (into-array java.nio.file.CopyOption
                       [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))))
    {:mesh-key mesh-key
     :tiers    (count tiers)
     :tris     (:triangle-count parsed)}))

(defn ensure!
  "Return cache metadata for `source`, preprocessing it if needed.

  Two concurrent callers for the same file produce one job, not two: the first
  installs a future and everyone else awaits it."
  [{:keys [^ConcurrentHashMap inflight ^ExecutorService pool] :as cache} ^File source]
  (let [k (.getAbsolutePath source)
        fut (.computeIfAbsent
             inflight k
             (reify java.util.function.Function
               (apply [_ _]
                 (CompletableFuture/supplyAsync
                  (reify java.util.function.Supplier
                    (get [_]
                      (let [mesh-key (sha256 source)
                            t0 (tier-file cache mesh-key 0)]
                        (if (.isFile t0)
                          (do (touch! t0) {:mesh-key mesh-key :cached true})
                          (let [r (preprocess! cache source mesh-key)]
                            (evict! cache)
                            r)))))
                  pool))))]
    (try @fut (finally (.remove inflight k fut)))))

;; --- component --------------------------------------------------------------

(defmethod ig/init-key :shipyard.mesh/cache
  [_ {:keys [crease-deg lod-tiers cap-bytes threads]}]
  (let [dir  (io/file (system/cache-home) "shipyard" "mesh")
        n    (resolve-threads threads)
        pool (Executors/newFixedThreadPool n)]
    (.mkdirs dir)
    (log/infof "mesh cache at %s (cap %,d bytes, %d threads)" (str dir) cap-bytes n)
    {:dir dir :crease-deg crease-deg :lod-tiers lod-tiers
     :cap-bytes cap-bytes :threads n :pool pool
     :inflight (ConcurrentHashMap.)}))

(defmethod ig/halt-key! :shipyard.mesh/cache [_ {:keys [^ExecutorService pool]}]
  (when pool (.shutdown pool)))
