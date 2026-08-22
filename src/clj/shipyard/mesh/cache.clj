(ns shipyard.mesh.cache
  "Lazy mesh preprocessing and the content-addressed .symesh disk cache.

  Scaffold: resolves settings and the pool size, creates the cache directory.
  Preprocessing, LRU eviction and single-flight are issue #13."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [shipyard.system :as system])
  (:import [java.util.concurrent ExecutorService Executors]))

(defn- resolve-threads [threads]
  (if (= :auto threads)
    ;; CPU-bound native and array work - a fixed pool, never virtual threads.
    (.availableProcessors (Runtime/getRuntime))
    threads))

(defmethod ig/init-key :shipyard.mesh/cache
  [_ {:keys [crease-deg lod-tiers cap-bytes threads]}]
  (let [dir  (io/file (system/cache-home) "shipyard" "mesh")
        n    (resolve-threads threads)
        pool (Executors/newFixedThreadPool n)]
    (.mkdirs dir)
    {:dir dir :crease-deg crease-deg :lod-tiers lod-tiers
     :cap-bytes cap-bytes :threads n :pool pool}))

(defmethod ig/halt-key! :shipyard.mesh/cache [_ {:keys [^ExecutorService pool]}]
  (when pool (.shutdown pool)))
