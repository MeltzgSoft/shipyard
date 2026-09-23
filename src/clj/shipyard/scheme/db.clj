(ns shipyard.scheme.db
  "Serialized EDN persistence; failed writes never publish memory."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [shipyard.scheme.transforms :as transforms]
            [shipyard.regions.migration :as migration]
            [shipyard.system :as system])
  (:import [java.io PushbackReader]
           [java.nio.file Files CopyOption StandardCopyOption AtomicMoveNotSupportedException FileSystemException]))

(defn open! [file]
  (let [value (if (fs/exists? file)
                (try
                  (with-open [reader (PushbackReader. (io/reader (fs/file file)))]
                    (let [value (edn/read {:eof ::eof} reader)]
                      (when-not (and (transforms/store? value) (= ::eof (edn/read {:eof ::eof} reader)))
                        (throw (ex-info "Invalid scheme store" {})))
                      value))
                  (catch Exception e
                    (throw (ex-info (str "Cannot read schemes at " file ". Restore a valid backup before restarting; the file was not modified.")
                                    {:code :invalid-store :file (str file)} e))))
                transforms/empty-store)]
    {:file (fs/absolutize file) :state (atom (update value :schemes #(into {} (map (fn [[id record]] [id (migration/scheme record)])) %)))}))

(defmethod ig/init-key :shipyard.scheme/db [_ {:keys [data-home]}]
  (open! (fs/path (or data-home (system/data-home!)) "shipyard" "schemes.edn")))

(defn snapshot! [{:keys [state]}] @state)

(defn- replace-file! [file value]
  (fs/create-dirs (fs/parent file))
  (when (and (fs/exists? file) (not (fs/regular-file? file)))
    (throw (ex-info "Scheme store destination must be a regular file" {:file (str file)})))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file) :prefix "schemes-" :suffix ".tmp"})]
    (try
      (spit (fs/file tmp) (pr-str value))
      (loop [attempt 1]
        (let [result (try
                       (Files/move (fs/path tmp) (fs/path file)
                                   (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                       :done
                       (catch AtomicMoveNotSupportedException e (throw e))
                       (catch FileSystemException e (if (< attempt system/move-attempts) :retry (throw e))))]
          (when (= result :retry)
            (Thread/sleep (* 50 (long attempt)))
            (recur (inc attempt)))))
      (finally (fs/delete-if-exists tmp)))))

(defn- transact! [{:keys [file state]} operation & args]
  (locking state
    (let [result (apply operation @state args)]
      (if (:error result)
        result
        (try
          (replace-file! file (:store result))
          (reset! state (:store result))
          (dissoc result :store)
          (catch Exception e
            {:error :store-write-failed
             :message (str "Could not save schemes at " file ". Check the folder permissions and retry. " (ex-message e))}))))))

(defn put! [store record mode] (transact! store transforms/put-record (migration/scheme record) mode))
(defn delete! [store id] (transact! store transforms/delete-record id))
