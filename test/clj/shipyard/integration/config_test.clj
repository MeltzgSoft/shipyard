(ns shipyard.integration.config-test
  "Issue #7 acceptance: configuration resolves through its layers, later
  winning over earlier.

  Four of them since issue #35: the library root moved out of the environment
  and into a file Shipyard writes itself."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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

(defn- with-library-setting
  "Write the settings form's file into a temp XDG config dir, and return it."
  [dir root]
  (system/save-library-root! (str dir) root)
  dir)

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

(deftest layer-3-the-settings-form-beats-user-config
  (let [dir (-> (with-user-config {:shipyard.library/index {:root "/from-user-config"}})
                (with-library-setting "/from-the-form"))
        cfg (system/load-config! {:config-dir (str dir) :env {}})]
    (is (= "/from-the-form" (get-in cfg [:shipyard.library/index :root]))
        "what the user set in the UI must beat what they once put in a file")
    (testing "and it does not disturb the config file it overrides"
      (is (= {:shipyard.library/index {:root "/from-user-config"}}
             (read-string (slurp (io/file dir "shipyard" "config.edn"))))))))

(deftest layer-4-env-beats-user-config
  (let [dir (with-user-config {:shipyard.http/server {:port 9999}})
        cfg (system/load-config! {:config-dir (str dir) :env {"PORT" "7777"}})]
    (is (= 7777 (get-in cfg [:shipyard.http/server :port]))
        "env var must beat a user config that set the same key")))

(deftest the-library-root-is-not-an-environment-variable
  ;; It is set from the settings form (issue #35). An env var outranking the
  ;; form would make the form lie about what the application is using.
  (let [dir (with-library-setting (with-user-config {}) "/from-the-form")
        cfg (system/load-config! {:config-dir (str dir)
                                  :env        {"SHIPYARD_LIBRARY" "/from-env"}})]
    (is (= "/from-the-form" (get-in cfg [:shipyard.library/index :root])))))

(deftest a-saved-root-survives-a-restart
  (let [dir (with-user-config {})]
    (system/save-library-root! (str dir) "/somewhere/models")
    (is (= "/somewhere/models"
           (get-in (system/load-config! {:config-dir (str dir) :env {}})
                   [:shipyard.library/index :root])))
    (testing "and saving again replaces it rather than accumulating"
      (system/save-library-root! (str dir) "/somewhere/else")
      (is (= "/somewhere/else"
             (get-in (system/load-config! {:config-dir (str dir) :env {}})
                     [:shipyard.library/index :root]))))))

(deftest an-unreadable-library-setting-does-not-stop-startup
  (let [dir (with-user-config {})]
    (spit (doto (io/file dir "shipyard" "library.edn") io/make-parents) "{{{ not edn")
    (is (nil? (get-in (system/load-config! {:config-dir (str dir) :env {}})
                      [:shipyard.library/index :root]))
        "a corrupt setting costs you the setting, not the application")))

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
