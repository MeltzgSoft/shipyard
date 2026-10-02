(ns shipyard.thumbnail.cache
  "Bounded background PNG rendering and a disposable, content-addressed disk cache."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.mesh.cache :as mesh]
            [shipyard.jobs :as workers]
            [shipyard.thumbnail.transforms :as t])
  (:import [java.nio.file CopyOption Files NoSuchFileException StandardCopyOption]))

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
                                 (re-matches #"[0-9a-f]{64}\.png" (str (fs/file-name file))))
                        (try {:file file :size (fs/size file)
                              :mtime (if (= (str file) (str protected)) Long/MAX_VALUE
                                         (fs/file-time->millis (fs/last-modified-time file)))}
                             (catch NoSuchFileException _ nil)))) (fs/list-dir dir))]
    (doseq [file (:files (mesh/eviction-plan files cap-bytes))]
      ;; Keep the newly rendered entry even with an impractically tiny cap.
      (when-not (= (str file) (str protected)) (Files/deleteIfExists (fs/path file))))))

(defn- publish! [{:keys [dir files-lock] :as cache} key bytes]
  (let [temp (fs/create-temp-file {:dir dir :prefix "png-" :suffix ".tmp"})
        target (png-file dir key)]
    (try
      (io/copy bytes (fs/file temp))
      (locking files-lock
        (Files/move (fs/path temp) (fs/path target)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (evict! cache target))
      (finally (Files/deleteIfExists (fs/path temp))))))

(defn- execute! [{:keys [jobs] :as cache} key render!]
  (try
    (publish! cache key (render!))
    (swap! jobs dissoc key)
    (catch Throwable error
      (log/warn error "thumbnail rendering failed:" key)
      (swap! jobs assoc key {:state :failed :at (System/currentTimeMillis)}))))

(defn request!
  "Return a cached key, or enqueue one render per content key without blocking HTTP.
  A full queue returns preparing; the normal thumbnail poll retries admission."
  [{:keys [scope jobs] :as cache} inputs render!]
  (let [key (t/cache-key inputs)]
    (if (file! cache key)
      {:state :ready :key key}
      (locking jobs
        (let [now (System/currentTimeMillis)]
          (swap! jobs #(into {} (remove (fn [[_ {:keys [state at]}]]
                                          (and (= state :failed) (> (- now at) 30000))) %)))
          (cond
            (file! cache key) {:state :ready :key key}
            (get @jobs key) (get @jobs key)
            :else
            (do
              (swap! jobs assoc key {:state :preparing})
              (when-not (workers/submit! scope #(execute! cache key render!))
                (swap! jobs dissoc key))
              {:state :preparing})))))))

(defmethod ig/init-key :shipyard.thumbnail/cache [_ {:keys [cache cap-bytes workers]}]
  (let [dir (fs/file (fs/parent (:dir cache)) "thumbnails")]
    (fs/create-dirs dir)
    {:dir dir :cap-bytes cap-bytes :files-lock (Object.) :jobs (atom {})
     :workers workers :scope (workers/scope! workers)}))

(defmethod ig/halt-key! :shipyard.thumbnail/cache [_ {:keys [scope]}]
  (workers/close! scope))
