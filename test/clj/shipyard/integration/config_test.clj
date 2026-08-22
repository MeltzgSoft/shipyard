(ns shipyard.integration.config-test
  "Issue #7 acceptance: configuration resolves through three layers, later
  winning over earlier."
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

(deftest layer-1-shipped-defaults
  (let [cfg (system/load-config {:config-dir "/nonexistent" :env {}})]
    (is (= 8080 (get-in cfg [:shipyard.http/server :port])))
    (is (= 35 (get-in cfg [:shipyard.mesh/cache :crease-deg])))
    (is (= [1.0 0.25 0.05] (get-in cfg [:shipyard.mesh/cache :lod-tiers])))))

(deftest layer-2-user-config-beats-shipped
  (let [dir (with-user-config {:shipyard.http/server {:port 9999}})
        cfg (system/load-config {:config-dir (str dir) :env {}})]
    (is (= 9999 (get-in cfg [:shipyard.http/server :port])))
    (testing "merge is deep - untouched sibling keys survive"
      (is (= "127.0.0.1" (get-in cfg [:shipyard.http/server :host]))))))

(deftest layer-3-env-beats-user-config
  (let [dir (with-user-config {:shipyard.http/server  {:port 9999}
                               :shipyard.library/index {:root "/from-user-config"}})
        cfg (system/load-config {:config-dir (str dir)
                                 :env        {"PORT" "7777"
                                              "SHIPYARD_LIBRARY" "/from-env"}})]
    (is (= 7777 (get-in cfg [:shipyard.http/server :port]))
        "env var must beat a user config that set the same key")
    (is (= "/from-env" (get-in cfg [:shipyard.library/index :root])))))

(deftest test-profile-shrinks-the-cache
  (let [d (system/load-config {:profile :default :config-dir "/nonexistent" :env {}})
        t (system/load-config {:profile :test    :config-dir "/nonexistent" :env {}})]
    (is (= 4294967296 (get-in d [:shipyard.mesh/cache :cap-bytes])))
    (is (= 67108864   (get-in t [:shipyard.mesh/cache :cap-bytes]))
        "the :test profile must give a small cap so LRU eviction is testable")))

(deftest tilde-in-library-root-is-expanded
  (let [dir (with-user-config {:shipyard.library/index {:root "~/models"}})
        cfg (system/load-config {:config-dir (str dir) :env {}})]
    (is (= (str (System/getProperty "user.home") "/models")
           (get-in cfg [:shipyard.library/index :root])))))
