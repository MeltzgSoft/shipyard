(ns shipyard.integration.tool-system-test
  "Small tool/fixture graphs own real stores and workers, including failed startup."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.http-fixture :as fixture]
            [shipyard.assembly-fixture :as assembly-fixture]
            [shipyard.library.index :as index]
            [shipyard.settings.db :as settings]
            [shipyard.system :as system])
  (:import [java.util.concurrent ExecutorService]
           [org.eclipse.jetty.server Server]))

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

(defmethod ig/init-key ::capture [_ {:keys [observed] :as resources}]
  (reset! observed (dissoc resources :observed)))

(deftest assembly-fixture-default-has-no-native-picker-or-listening-server
  (let [started (assembly-fixture/start!)
        sys (:system started)]
    (try
      (is (nil? (:shipyard.file-picker/db sys)))
      (is (nil? (:shipyard.http/server sys)))
      (is (= 200 (:status ((:handler started) {:request-method :get :uri "/"}))))
      (is (= (str (:root started)) (index/root! (:shipyard.library/index sys))))
      (finally (assembly-fixture/stop! started)))))

(deftest assembly-fixture-cleans-up-failed-library-construction-and-initialization
  (let [home (atom nil)
        build! (fn [root] (reset! home (fs/parent root)) (assembly-fixture/library! root))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Library setup failed"
                          (assembly-fixture/start! false
                                                   (fn [root]
                                                     (reset! home (fs/parent root))
                                                     (fs/create-dirs root)
                                                     (throw (ex-info "Library setup failed" {}))))))
    (is (not (fs/exists? @home)))
    (let [failure (try (assembly-fixture/start! false build! assembly-fixture/author!
                                                {::failure {:jobs (ig/ref :shipyard.http/jobs)
                                                            :catalog (ig/ref :shipyard.catalog/db)}})
                       nil (catch Exception error error))
          partial (:system (ex-data failure))
          workers ^ExecutorService (get-in partial [:shipyard.jobs/pool :pool])
          database (:shipyard.store/db partial)]
      (is (some? database))
      (is (some? workers))
      (when database (is (d/closed? (:conn database))))
      (when workers (is (.isTerminated workers)))
      (is (not (fs/exists? @home)))
      (is (empty? (.getSuppressed ^Throwable failure)) "repeated cleanup must not introduce another failure"))))

(deftest assembly-fixture-halts-real-resources-on-post-start-authoring-failure
  (let [observed (atom nil) home (atom nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Authoring failed"
                          (assembly-fixture/start!
                           true
                           (fn [root] (reset! home (fs/parent root)) (assembly-fixture/library! root))
                           (fn [_] (throw (ex-info "Authoring failed" {})))
                           {::capture {:observed observed
                                       :store (ig/ref :shipyard.store/db)
                                       :workers (ig/ref :shipyard.jobs/pool)
                                       :server (ig/ref :shipyard.http/server)}})))
    (let [{:keys [store workers server]} @observed]
      (is (some? store) "authoring follows initialization of the real resources")
      (is (some? workers))
      (is (some? server))
      (when store (is (d/closed? (:conn store))))
      (when workers (is (.isTerminated ^ExecutorService (:pool workers))))
      (when server (is (.isStopped ^Server server))))
    (is (not (fs/exists? @home)))))
