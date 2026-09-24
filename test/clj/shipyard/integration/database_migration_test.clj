(ns shipyard.integration.database-migration-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.store.db :as store]
            [shipyard.store.migrate :as migrate]
            [taoensso.nippy :as nippy]))

(deftest dense-paint-survives-export-import-and-repeated-saves
  (let [started (fixture/start!) cat (:shipyard.catalog/db (:system started))
        id (:weapon fixture/ids) temp (fs/create-temp-dir {:prefix "shipyard-native-migration-"})
        source (fs/path temp "source") destination (fs/path temp "destination")
        backup (fs/path temp "backup.nippy")
        region {:version 2 :mesh-key (apply str (repeat 64 "a")) :revision 1
                :layers ["Primary" "Secondary"] :layer-definitions {}
                :faces (into {} (map (fn [i] [(format "%072x" i) "Secondary"])) (range 56137))}]
    (try
      (catalog/save-regions! cat id region)
      (let [before (catalog/snapshot! cat)]
        (fs/create-dir source)
        (store/read! (:store cat) #(d/copy % (str source)))
        (let [exported (migrate/export! source backup)
              imported (migrate/import! backup destination)]
          (is (= (:datoms exported) (:verified-datoms imported))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists" (migrate/export! source backup)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists" (migrate/import! backup source)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists" (migrate/import! backup destination)))
        (let [database (store/open! destination)
              migrated (assoc cat :store database)]
          (try
            (is (= before (catalog/snapshot! migrated)))
            (dotimes [i 6]
              (let [current (catalog/part-regions (catalog/part (catalog/snapshot! migrated) id))
                    next-region (cond-> (update current :revision inc)
                                  (even? i) (update :faces dissoc (format "%072x" 0))
                                  (odd? i) (assoc-in [:faces (format "%072x" 0)] "Secondary"))]
                (catalog/save-regions! migrated id next-region (:revision current))))
            (finally (store/close! database))))
        (let [database (store/open! destination)]
          (try
            (let [reopened (catalog/snapshot! (assoc cat :store database))
                  painted (catalog/part-regions (catalog/part reopened id))]
              (is (= 7 (:revision painted)))
              (is (= (:faces region) (:faces painted))))
            (finally (store/close! database))))
        (is (= before (catalog/snapshot! cat)) "The source database is never modified"))
      (finally (fixture/stop! started) (fs/delete-tree temp)))))

(deftest migration-refuses-invalid-input-before-creating-a-database
  (let [temp (fs/create-temp-dir {:prefix "shipyard-invalid-migration-"})
        input (fs/path temp "invalid.nippy") destination (fs/path temp "database")]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data.mdb" (migrate/export! destination input)))
      (is (not (fs/exists? destination)))
      (nippy/freeze-to-file (str input) {:shipyard/export-version 99})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid Shipyard" (migrate/import! input destination)))
      (is (not (fs/exists? destination)))
      (finally (fs/delete-tree temp)))))

(deftest old-native-format-has-an-actionable-startup-error
  (let [cause (ex-info "Fail to open database: MDB_VERSION_MISMATCH" {})]
    (with-redefs [d/get-conn (fn [& _] (throw cause))]
      (let [error (try (store/open! "/tmp/unopened-shipyard-database") (catch Exception e e))]
        (is (= :native-format-mismatch (:type (ex-data error))))
        (is (identical? cause (ex-cause error)))
        (is (re-find #":db-v1 export/import" (ex-message error)))))))
