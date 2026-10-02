(ns shipyard.thumbnail.cache
  "Bounded background PNG rendering and a disposable, content-addressed disk cache."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.mesh.cache :as mesh]
            [shipyard.jobs :as workers]
            [shipyard.thumbnail.transforms :as t])
  (:import [java.io FileNotFoundException]
           [java.nio.file CopyOption Files NoSuchFileException StandardCopyOption]))

(defn- png-file [dir key] (fs/file dir (str key ".png")))

(defn file!
  "Return and touch a complete PNG. Missing/externally cleared entries are cache misses."
  [{:keys [dir files-lock]} key]
  (locking files-lock
    (let [file (png-file dir key)]
      (try
        (when (fs/regular-file? file)
          (fs/set-last-modified-time file (System/currentTimeMillis))
          file)
        (catch NoSuchFileException _ nil)))))

(defn- evict! [{:keys [dir cap-bytes]} protected]
  (let [files (keep (fn [file]
                      (when (and (fs/regular-file? file)
                                 (re-matches #"[0-9a-f]{64}\.(png|ref)" (str (fs/file-name file))))
                        (try {:file file :size (fs/size file)
                              :mtime (if (contains? protected (str file)) Long/MAX_VALUE
                                         (fs/file-time->millis (fs/last-modified-time file)))}
                             (catch NoSuchFileException _ nil)))) (fs/list-dir dir))]
    (doseq [file (:files (mesh/eviction-plan files cap-bytes))]
      ;; Keep the newly rendered entry even with an impractically tiny cap.
      (when-not (contains? protected (str file)) (Files/deleteIfExists (fs/path file))))))

(defn- publish! [{:keys [dir files-lock] :as cache} key bytes]
  (let [temp (fs/create-temp-file {:dir dir :prefix "png-" :suffix ".tmp"})
        target (png-file dir key)]
    (try
      (io/copy bytes (fs/file temp))
      (locking files-lock
        (Files/move (fs/path temp) (fs/path target)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (evict! cache #{(str target)}))
      (finally (Files/deleteIfExists (fs/path temp))))))

(defn- execute! [{:keys [jobs]} key task!]
  (try
    (task!)
    (swap! jobs dissoc key)
    (catch Throwable error
      (log/warn error "thumbnail rendering failed:" key)
      (swap! jobs assoc key {:state :failed :at (System/currentTimeMillis)}))))

(defn- ensure-image! [{:keys [renders] :as cache} key render!]
  (when-not (file! cache key)
    (let [mine (promise)
          owner (get (swap! renders #(if (contains? % key) % (assoc % key mine))) key)]
      (if (identical? mine owner)
        (try
          (when-not (file! cache key) (publish! cache key (render!)))
          (deliver mine nil)
          (catch Throwable error (deliver mine error) (throw error))
          (finally (swap! renders dissoc key)))
        (when-let [error @owner] (throw error))))))

(defn- enqueue! [{:keys [scope jobs] :as cache} key task!]
  (locking jobs
    (let [now (System/currentTimeMillis)]
      (swap! jobs #(into {} (remove (fn [[_ {:keys [state at]}]]
                                      (and (= state :failed) (> (- now at) 30000))) %)))
      (or (get @jobs key)
          (do
            (swap! jobs assoc key {:state :preparing})
            (when-not (workers/submit! scope #(execute! cache key task!))
              (swap! jobs dissoc key))
            {:state :preparing})))))

(defn request!
  "Return a cached key, or enqueue one render per content key without blocking HTTP.
  A full queue returns preparing; the normal thumbnail poll retries admission."
  [cache inputs render!]
  (let [key (t/cache-key inputs)]
    (if (file! cache key)
      {:state :ready :key key}
      (enqueue! cache key #(ensure-image! cache key render!)))))

(defn- reference-file [dir key] (fs/file dir (str key ".ref")))

(defn- referenced-key! [{:keys [dir files-lock] :as cache} lookup]
  (locking files-lock
    (let [file (reference-file dir lookup)]
      (try
        (when (fs/regular-file? file)
          (let [key (slurp file)]
            (when (and (re-matches #"[0-9a-f]{64}" key) (file! cache key))
              (fs/set-last-modified-time file (System/currentTimeMillis))
              key)))
        (catch FileNotFoundException _ nil)
        (catch NoSuchFileException _ nil)))))

(defn- publish-reference! [{:keys [dir files-lock] :as cache} lookup key]
  (let [temp (fs/create-temp-file {:dir dir :prefix "ref-" :suffix ".tmp"})
        target (reference-file dir lookup)]
    (try
      (spit (str temp) key)
      (locking files-lock
        (Files/move (fs/path temp) (fs/path target)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (evict! cache #{(str target) (str (png-file dir key))}))
      (finally (Files/deleteIfExists (fs/path temp))))))

(defn request-derived!
  "Resolve a cheap, versioned source stamp to a content-addressed PNG. References
  survive restart; dense input reads, hashing and rendering only run on a miss.
  prepare! returns immutable :inputs and a :render! function for those inputs."
  [cache stamp prepare!]
  (let [lookup (t/cache-key stamp)]
    (if-let [key (referenced-key! cache lookup)]
      {:state :ready :key key}
      (enqueue! cache (str "lookup-" lookup)
                #(when-not (referenced-key! cache lookup)
                   (let [{:keys [inputs render!]} (prepare!)
                         key (t/cache-key inputs)]
                     (ensure-image! cache key render!)
                     (publish-reference! cache lookup key)))))))

(defmethod ig/init-key :shipyard.thumbnail/cache [_ {:keys [cache cap-bytes workers]}]
  (let [dir (fs/file (fs/parent (:dir cache)) "thumbnails")]
    (fs/create-dirs dir)
    {:dir dir :cap-bytes cap-bytes :files-lock (Object.) :jobs (atom {}) :renders (atom {})
     :workers workers :scope (workers/scope! workers)}))

(defmethod ig/halt-key! :shipyard.thumbnail/cache [_ {:keys [scope]}]
  (workers/close! scope))
