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
    (locking jobs (swap! jobs dissoc key))
    (catch Throwable error
      (log/warn error "thumbnail rendering failed:" key)
      (locking jobs (swap! jobs assoc key {:state :failed :message (or (ex-message error) "Rendering failed")}))
      (throw error))))

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
    (or (get @jobs key)
        (do
          (swap! jobs assoc key {:state :preparing})
          (let [result (workers/submit-batch! scope [{:key key :run! execute! :args [cache key task!]}])]
            (if (:accepted? result)
              {:state :preparing}
              (do (swap! jobs dissoc key)
                  {:state :overloaded :message "Background queue is full. Retry after pending work finishes."})))))))

(defn request!
  "Return a cached key, or enqueue one render per content key without blocking HTTP.
  Overload is observable; failed renders are retained without automatic retries."
  [cache inputs render!]
  (let [key (t/cache-key inputs)]
    (if (file! cache key)
      {:state :ready :key key}
      (enqueue! cache key #(ensure-image! cache key render!)))))

(defn- reference-file [dir key] (fs/file dir (str key ".ref")))

(defn referenced-key! [{:keys [dir files-lock] :as cache} lookup]
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

(defn retry-derived! [{:keys [jobs]} stamp]
  (let [key (str "lookup-" (t/cache-key stamp))]
    (locking jobs
      (when (= :failed (:state (get @jobs key))) (swap! jobs dissoc key)))))

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

(defn request-batch!
  "Atomically admit all cache misses; a batch holds only stamps and deferred preparation."
  [{:keys [scope jobs] :as cache} requests]
  (locking jobs
    (let [missing (filterv (fn [{:keys [stamp]}]
                             (let [lookup (t/cache-key stamp) key (str "lookup-" lookup)]
                               (and (not (referenced-key! cache lookup)) (not (get @jobs key))))) requests)
          descriptors (mapv (fn [{:keys [stamp prepare!]}]
                              (let [lookup (t/cache-key stamp) key (str "lookup-" lookup)]
                                {:key key :run! execute!
                                 :args [cache key (fn []
                                                    (when-not (referenced-key! cache lookup)
                                                      (let [{:keys [inputs render!]} (prepare!)
                                                            content (t/cache-key inputs)]
                                                        (ensure-image! cache content render!)
                                                        (publish-reference! cache lookup content))))]})) missing)
          result (workers/submit-batch! scope descriptors)]
      (when (:accepted? result)
        ;; Workers can start now, but completion shares this jobs lock.
        ;; Install claims before releasing it.
        (swap! jobs into (map (fn [descriptor] [(:key descriptor) {:state :preparing}]) descriptors)))
      result)))

(defn fork!
  "A cancellable preview owner sharing content files, rendering dedup and resource limits."
  [cache priority]
  (let [scope (workers/scope! (:workers cache) {:priority priority})]
    (swap! (:children cache) conj scope)
    (assoc cache :scope scope :jobs (atom {}) :parent cache)))

(defn progress! [cache]
  (reduce (partial merge-with +) {:running 0 :queued 0}
          (map workers/progress! (conj @(:children cache) (:scope cache)))))

(defn close! [{:keys [scope parent]}]
  (workers/close! scope)
  (when parent (swap! (:children parent) disj scope)))

(defn reopen! [{:keys [scope jobs parent]}]
  (workers/reopen! scope)
  (reset! jobs {})
  (when parent (swap! (:children parent) conj scope)))

(defmethod ig/init-key :shipyard.thumbnail/cache [_ {:keys [cache cap-bytes workers]}]
  (let [dir (fs/file (fs/parent (:dir cache)) "thumbnails")]
    (fs/create-dirs dir)
    {:dir dir :cap-bytes cap-bytes :files-lock (Object.) :jobs (atom {}) :renders (atom {})
     :workers workers :children (atom #{}) :scope (workers/scope! workers)}))

(defmethod ig/halt-key! :shipyard.thumbnail/cache [_ cache]
  (doseq [scope @(:children cache)] (workers/close! scope))
  (close! cache))
