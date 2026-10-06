(ns shipyard.integration.tool-system-test
  "Small tool/fixture graphs own real stores and workers, including failed startup."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.http-fixture :as fixture]
            [shipyard.library.index :as index]
            [shipyard.settings.db :as settings]
            [shipyard.system :as system])
  (:import [java.util.concurrent ExecutorService]))

(defmethod ig/init-key ::failure [_ _]
  (throw (ex-info "Deliberate failure after resources open" {})))

(deftest explicit-tool-root-does-not-change-the-application-selection
  (let [home (fs/create-temp-dir {:prefix "shipyard-tool-root-"})
        saved (str (fs/create-dirs (fs/path home "saved")))
        explicit (str (fs/create-dirs (fs/path home "explicit")))
        directory (str (fs/path home "database"))
        config (system/read-config! "systems/library.edn"
                                    {:shipyard.store/db {:directory directory}
                                     :shipyard.library/index {:root explicit}})]
    (try
      (let [seed (system/start! (system/read-config! "systems/store.edn"
                                                     {:shipyard.store/db {:directory directory}}))]
        (try (settings/save-library-root! (:shipyard.store/db seed) saved)
             (finally (system/stop! seed))))
      (let [started (system/start! config)]
        (try
          (is (= explicit (index/root! (:shipyard.library/index started))))
          (is (= saved (settings/library-root! (:shipyard.store/db started))))
          (finally (system/stop! started))))
      (let [fresh (system/start! (assoc-in config [:shipyard.store/db :directory]
                                           (str (fs/path home "fresh-database"))))]
        (try
          (is (= explicit (index/root! (:shipyard.library/index fresh))))
          (is (nil? (settings/library-root! (:shipyard.store/db fresh))))
          (finally (system/stop! fresh))))
      (finally (fs/delete-tree home)))))

(deftest failed-fixture-graph-startup-closes-workers-and-store
  (let [home (fs/create-temp-dir {:prefix "shipyard-partial-fixture-"})
        config (system/read-config! "shipyard/systems/http.edn"
                                    {:shipyard.store/db {:directory (str (fs/path home "database"))}
                                     :shipyard.mesh/cache {:cache-home (str home)}
                                     ::failure {:jobs (ig/ref :shipyard.http/jobs)
                                                :catalog (ig/ref :shipyard.catalog/db)}})]
    (try
      (let [failure (try (system/start! config) nil (catch Exception error error))
            started (:system (ex-data failure))
            database (:shipyard.store/db started)
            workers ^ExecutorService (get-in started [:shipyard.jobs/pool :pool])]
        (is (some? failure))
        (is (some? database) "the real store opened before the dependent component failed")
        (is (some? workers))
        (when database (is (d/closed? (:conn database))))
        (when workers (is (.isTerminated workers))))
      (finally (fs/delete-tree home)))))

(deftest stopping-http-fixture-drains-its-workers-before-removing-temporary-files
  (let [started (fixture/start! nil)
        database (:store (:library started))
        workers ^ExecutorService (:pool (:workers started))
        directory (:directory database)]
    (try
      (fixture/stop! started)
      (is (.isTerminated workers))
      (is (d/closed? (:conn database)))
      (is (not (fs/exists? directory)))
      (finally
        (when-not (d/closed? (:conn database)) (fixture/stop! started))))))
