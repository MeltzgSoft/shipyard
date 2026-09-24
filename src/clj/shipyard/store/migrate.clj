(ns shipyard.store.migrate
  "Offline native-format migration. Export with :db-v1; import in a separate JVM
  using the current native library. Never replace or delete the source database."
  (:require [babashka.fs :as fs]
            [datalevin.core :as d]
            [taoensso.nippy :as nippy])
  (:import [java.nio.file Files OpenOption StandardOpenOption]))

(defn- facts [db]
  (mapv (fn [datom] [(:e datom) (:a datom) (:v datom)]) (d/datoms db :eav)))

(defn export!
  "Export a stopped Shipyard database to a new local snapshot file."
  [directory output]
  (when-not (fs/regular-file? (fs/path directory "data.mdb"))
    (throw (ex-info "Database directory does not contain data.mdb" {:directory (str directory)})))
  (when (fs/exists? output)
    (throw (ex-info "Export destination already exists" {:path (str output)})))
  (let [conn (d/get-conn (str directory))]
    (try
      (when-not (= 1 (:store/version (d/pull @conn [:store/version] [:store/key "shipyard"])))
        (throw (ex-info "Expected a Shipyard version-1 database" {})))
      (let [datoms (facts @conn)
            payload {:shipyard/export-version 1 :schema (d/schema conn) :datoms datoms}]
        ;; CREATE_NEW also refuses a destination created since the earlier check.
        (Files/write (fs/path output) (nippy/freeze payload)
                     ^"[Ljava.nio.file.OpenOption;" (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
        {:datoms (count datoms) :snapshot (str output)})
      (finally (d/close conn)))))

(defn import!
  "Import our trusted local export into a new directory, close/reopen it, and
  verify every entity/attribute/value and the schema before reporting success."
  [input directory]
  (when (fs/exists? directory)
    (throw (ex-info "Import destination already exists; use a new directory" {:directory (str directory)})))
  (let [{:keys [schema datoms] :as payload} (nippy/thaw-from-file (str input))]
    (when-not (and (= 1 (:shipyard/export-version payload)) (map? schema) (vector? datoms)
                   (every? #(and (vector? %) (= 3 (count %)) (pos-int? (first %)) (keyword? (second %))) datoms)
                   (some #(= [:store/key "shipyard"] (subvec % 1)) datoms))
      (throw (ex-info "Invalid Shipyard database export" {:path (str input)})))
    (fs/create-dir directory)
    (let [db (d/init-db (mapv #(apply d/datom %) datoms) (str directory) schema
                        {:validate-data? true :closed-schema? true})]
      (d/close-db db))
    (let [conn (d/get-conn (str directory))]
      (try
        (when-not (and (= (set datoms) (set (facts @conn)))
                       (= schema (d/schema conn)))
          (throw (ex-info "Imported database verification failed; original database is unchanged"
                          {:directory (str directory)})))
        {:verified-datoms (count datoms) :directory (str directory)}
        (finally (d/close conn))))))

(defn -main [& args]
  (let [[operation source destination] args]
    (when-not (and (= 3 (count args)) (#{"export" "import"} operation))
      (throw (ex-info "Usage: clojure -M[:db-v1] -m shipyard.store.migrate export|import SOURCE DESTINATION" {})))
    (prn ((case operation "export" export! "import" import!) source destination))))
