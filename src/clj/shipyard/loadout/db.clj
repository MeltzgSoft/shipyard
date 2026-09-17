(ns shipyard.loadout.db
  "One serialized EDN persistence boundary. Publish memory only after durable replacement."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [shipyard.loadout.transforms :as transforms]
            [shipyard.system :as system])
  (:import [java.io PushbackReader]
           [java.nio.file AtomicMoveNotSupportedException FileSystemException]))

(defn open! [file]
  (let [value (if (fs/exists? file)
                (try
                  (with-open [reader (PushbackReader. (io/reader (fs/file file)))]
                    (let [value (edn/read {:eof ::eof} reader)]
                      (when-not (and (= ::eof (edn/read {:eof ::eof} reader)) (transforms/store? value))
                        (throw (ex-info "Invalid loadout store" {})))
                      value))
                  (catch Exception e
                    (throw (ex-info (str "Cannot read loadouts at " file ". Restore a valid backup before restarting; the file was not modified.")
                                    {:code :invalid-store :file (str file)} e))))
                transforms/empty-store)]
    {:file (fs/absolutize file) :state (atom value)}))

(defmethod ig/init-key :shipyard.loadout/db [_ {:keys [data-home]}]
  (open! (fs/path (or data-home (system/data-home!)) "shipyard" "loadouts.edn")))

(defn snapshot! [{:keys [state]}] @state)

(defn- replace-file! [file value]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file) :prefix "loadouts-" :suffix ".tmp"})]
    (try
      (spit (fs/file tmp) (pr-str value))
      (loop [attempt 1]
        (let [result (try
                       (fs/move tmp file {:replace-existing true :atomic-move true})
                       :done
                       (catch AtomicMoveNotSupportedException e (throw e))
                       (catch FileSystemException e
                         (if (< attempt system/move-attempts) :retry (throw e))))]
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
             :message (str "Could not update loadouts at " file ". Check the folder permissions and retry. " (ex-message e))}))))))

(defn put! [store record mode]
  (transact! store transforms/put-record record mode))

(defn delete! [store id]
  (transact! store transforms/delete-record id))
