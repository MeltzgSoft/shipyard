(ns shipyard.library.index
  "Library root plus the mtime+size scan cache (TECHNICAL.md §5.4).

  Two keys, two purposes. `:part/id` is the folder path, free during the walk.
  `:part/mesh-key` is the SHA-256 of the source STL and is computed only when a
  part is first preprocessed - hashing 19 GB at every start is exactly what this
  namespace exists to prevent."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.library.scan :as scan]
            [shipyard.system :as system]))

(defn index-file [] (fs/file (system/cache-home) "shipyard" "index.edn"))

(defn write-atomically!
  "Write via a temp file and rename, so a concurrent reader never sees a partial
  file.

  `:atomic-move` is not supported on every filesystem, so fall back rather than
  fail: the consequence is a torn read under concurrency, not corruption, and
  refusing to start is worse."
  [target ^String content]
  (fs/create-dirs (fs/parent target))
  (let [tmp (fs/create-temp-file {:dir (fs/parent target) :prefix "shipyard-" :suffix ".tmp"})]
    (spit (fs/file tmp) content)
    (try
      (fs/move tmp target {:atomic-move true :replace-existing true})
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (fs/move tmp target {:replace-existing true})))))

(defn load-index [f]
  (if (fs/regular-file? f)
    (try (edn/read-string (slurp f))
         (catch Exception e
           ;; Derived data: a corrupt index costs a rescan, never correctness.
           (log/warn "discarding unreadable scan index:" (ex-message e))
           {}))
    {}))

(defn save-index! [f m] (write-atomically! f (pr-str m)))

(defn- stat [f] {:mtime (fs/file-time->millis (fs/last-modified-time f)) :size (fs/size f)})

(defn fresh?
  "An entry survives only while its source file's mtime and size both match.
  Re-pitting a hull changes both, so the cache self-invalidates."
  [entry source]
  (and entry (= (select-keys entry [:mtime :size]) (stat source))))

(defn name-of [variant]
  (case variant
    :unsupported-pitted "unsupported-pitted.stl"
    :unsupported        "unsupported.stl"
    :supported          "supported.stl"))

(defn refresh
  "Merge a fresh scan against the stored index, carrying forward `:mesh-key` for
  parts whose source file is unchanged and dropping it for everything else."
  [parts root stored]
  (reduce
   (fn [acc {:part/keys [id source]}]
     (if-not source
       (assoc acc id (dissoc (get stored id) :mesh-key))   ; nothing renderable
       (let [f   (fs/file root id (name-of source))
             old (get stored id)]
         (assoc acc id
                (merge (stat f)
                       (when (fresh? old f)
                         (select-keys old [:mesh-key :tris])))))))
   {}
   parts))

(defmethod ig/init-key :shipyard.library/index [_ {:keys [root]}]
  (let [dir (fs/file root)]
    (when-not (fs/directory? dir)
      ;; Not fatal: the app must still start so the user can point it somewhere
      ;; real. A hard failure here makes a fresh install unusable.
      (log/warn "library root does not exist:" root))
    (let [f      (index-file)
          stored (load-index f)
          parts  (or (scan/scan dir) [])
          idx    (refresh parts root stored)]
      (log/infof "library: %d parts, %d with a cached mesh key"
                 (count parts) (count (filter :mesh-key (vals idx))))
      (when-not (= idx stored) (save-index! f idx))
      {:root root :available (fs/directory? dir) :parts parts
       :index (atom idx) :index-file f})))
