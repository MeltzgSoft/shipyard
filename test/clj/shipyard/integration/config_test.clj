(ns shipyard.integration.config-test
  "Issue #7 acceptance: configuration resolves through its layers, later
  winning over earlier.

  The selected library lives in Datalevin; bootstrap configuration remains a file."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.cli :as cli]
            [shipyard.settings.db :as settings]
            [shipyard.store.db :as store]
            [shipyard.system :as system]))

(defn- with-user-config
  "Write a user config into a temp XDG config dir and return the dir."
  [edn]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "shipyard-cfg-" (random-uuid)))
        f   (io/file dir "shipyard" "config.edn")]
    (io/make-parents f)
    (spit f (pr-str edn))
    dir))

(defn- with-store [dir f]
  (let [database (store/open! (io/file dir "database"))]
    (try (f database) (finally (store/close! database)))))

(defn- legacy-setting! [dir value]
  (spit (doto (io/file dir "shipyard" "library.edn") io/make-parents) value))

(deftest layer-1-shipped-defaults
  (let [cfg (system/load-config! {:config-dir "/nonexistent" :env {}})]
    (is (nil? (get-in cfg [:shipyard.library/index :root]))
        "there is no default library: a fresh install must ask, not guess")
    (is (= 8080 (get-in cfg [:shipyard.http/server :port])))
    (is (= 35 (get-in cfg [:shipyard.mesh/cache :crease-deg])))
    (is (= [1.0 0.25 0.05] (get-in cfg [:shipyard.mesh/cache :lod-tiers])))
    (is (= 1.0 (get-in cfg [:shipyard.mesh/cache :facet-angle-deg])))
    (is (= 0.01 (get-in cfg [:shipyard.mesh/cache :facet-plane-epsilon-mm])))))

(deftest layer-2-user-config-beats-shipped
  (let [dir (with-user-config {:shipyard.http/server {:port 9999}})
        cfg (system/load-config! {:config-dir (str dir) :env {}})]
    (is (= 9999 (get-in cfg [:shipyard.http/server :port])))
    (testing "merge is deep - untouched sibling keys survive"
      (is (= "127.0.0.1" (get-in cfg [:shipyard.http/server :host]))))))

(deftest database-selection-beats-bootstrap-config-without-rewriting-it
  (let [dir (with-user-config {:shipyard.library/index {:root "/from-user-config"}})
        file (io/file dir "shipyard" "config.edn")
        original (str "; keep this comment and formatting\n" (slurp file) "\n")]
    (spit file original)
    (with-store dir
      (fn [database]
        (settings/save-library-root! database "/from-the-form")
        (let [cfg (system/load-config! {:config-dir (str dir) :env {}})]
          (is (= "/from-user-config" (get-in cfg [:shipyard.library/index :root])))
          (is (= "/from-the-form"
                 (settings/initial-root! database (get-in cfg [:shipyard.library/index :root]) (str dir)))))))
    (is (= original (slurp file)))
    (is (not (fs/exists? (io/file dir "shipyard" "library.edn"))))))

(deftest legacy-selection-is-imported-once-and-database-is-authoritative
  (let [dir (with-user-config {})
        legacy "{:root \"/legacy/models\"}\n"]
    (legacy-setting! dir legacy)
    (with-store dir
      (fn [database]
        (is (= "/legacy/models" (settings/initial-root! database "/configured/models" (str dir))))))
    (is (= legacy (slurp (io/file dir "shipyard" "library.edn"))))
    (legacy-setting! dir "{:root \"/changed/legacy\"}")
    (with-store dir
      (fn [database]
        (is (= "/legacy/models" (settings/initial-root! database "/changed/config" (str dir))))
        (settings/save-library-root! database "/from-the-form")))
    (with-store dir
      (fn [database]
        (is (= "/from-the-form" (settings/initial-root! database nil (str dir))))))))

(deftest layer-4-env-beats-user-config
  (let [dir (with-user-config {:shipyard.http/server {:port 9999}})
        cfg (system/load-config! {:config-dir (str dir) :env {"PORT" "7777"}})]
    (is (= 7777 (get-in cfg [:shipyard.http/server :port]))
        "env var must beat a user config that set the same key")))

(deftest the-library-root-is-not-an-environment-variable
  (let [dir (with-user-config {})
        cfg (system/load-config! {:config-dir (str dir) :env {"SHIPYARD_LIBRARY" "/from-env"}})]
    (is (nil? (get-in cfg [:shipyard.library/index :root])))
    (with-store dir
      (fn [database]
        (settings/save-library-root! database "/from-the-form")
        (is (= "/from-the-form" (settings/initial-root! database nil (str dir))))))))

(deftest a-bootstrap-root-is-persisted-without-creating-a-settings-file
  (let [dir (with-user-config {})]
    (with-store dir #(is (= "/configured/models" (settings/initial-root! % "/configured/models" (str dir)))))
    (with-store dir #(is (= "/configured/models" (settings/initial-root! % "/different/models" (str dir)))))
    (is (not (fs/exists? (io/file dir "shipyard" "library.edn"))))))

(deftest an-unreadable-legacy-setting-does-not-stop-startup
  (let [dir (with-user-config {})]
    (legacy-setting! dir "{{{ not edn")
    (with-store dir
      (fn [database]
        (is (nil? (settings/initial-root! database nil (str dir))))
        (is (= "/configured/models" (settings/initial-root! database "/configured/models" (str dir))))))))

(deftest test-profile-shrinks-the-cache
  (let [d (system/load-config! {:profile :default :config-dir "/nonexistent" :env {}})
        t (system/load-config! {:profile :test    :config-dir "/nonexistent" :env {}})]
    (is (= 4294967296 (get-in d [:shipyard.mesh/cache :cap-bytes])))
    (is (= 67108864   (get-in t [:shipyard.mesh/cache :cap-bytes]))
        "the :test profile must give a small cap so LRU eviction is testable")))

(deftest tilde-in-library-root-is-expanded
  (let [dir (with-user-config {:shipyard.library/index {:root "~/models"}})
        cfg (system/load-config! {:config-dir (str dir) :env {}})]
    (is (= (str (System/getProperty "user.home") "/models")
           (get-in cfg [:shipyard.library/index :root])))))

(deftest command-line-root-lookup-is-read-only-and-prefers-the-database
  (let [dir (with-user-config {})
        database-dir (io/file dir "database")
        cfg {:shipyard.store/db {:directory (str database-dir)}
             :shipyard.library/index {:root "/configured/models" :config-dir (str dir)}}]
    (is (= "/configured/models" (cli/configured-root! cfg)))
    (is (not (fs/exists? database-dir)) "a read-only lookup must not create a database")
    (legacy-setting! dir "{:root \"/legacy/models\"}")
    (is (= "/legacy/models" (cli/configured-root! cfg)))
    (is (not (fs/exists? database-dir)))
    (with-store dir #(settings/save-library-root! % "/saved/models"))
    (is (= "/saved/models" (cli/configured-root! cfg)))))

(deftest command-line-root-lookup-does-not-close-a-running-application-store
  (let [dir (with-user-config {})
        cfg {:shipyard.store/db {:directory (str (io/file dir "database"))}
             :shipyard.library/index {:config-dir (str dir)}}]
    (with-store dir
      (fn [database]
        (settings/save-library-root! database "/saved/models")
        (is (= "/saved/models" (cli/configured-root! cfg)))
        (is (= "/saved/models" (settings/library-root! database))
            "closing the lookup connection must leave the application's connection readable")
        (settings/save-library-root! database "/changed/models")
        (is (= "/changed/models" (settings/library-root! database)))
        (is (= "/changed/models" (cli/configured-root! cfg)))))))
