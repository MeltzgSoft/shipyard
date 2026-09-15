(ns shipyard.integration.loadout-store-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.loadout.store :as store])
  (:import [java.util UUID]))

(defn- temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "shipyard-loadouts-" (random-uuid)))
    (.mkdirs)))

(defn- loadout []
  #:loadout{:id (UUID/randomUUID) :name "Dominator" :hull "human/cruiser/hull"
            :slots {[[:prow 0]] "human/cruiser/prow"
                    [[:weapon 0] [:turret 0]] "human/cruiser/turret"}
            :scheme nil :thumb nil})

(deftest persists-the-full-path-keyed-representation
  (let [data-home (temp-dir)
        first (ig/init-key :shipyard.loadout/store {:data-home data-home})
        saved (loadout)]
    (store/replace! first [saved])
    (testing "the store is durable and explicit about its format"
      (is (= {:shipyard/version 1 :loadouts [saved]}
             (edn/read-string (slurp (store/loadout-file data-home)))))
      (is (= [saved] (store/loadouts (ig/init-key :shipyard.loadout/store {:data-home data-home})))))))

(deftest malformed-or-incomplete-data-is-never-silently-discarded
  (let [data-home (temp-dir)
        file (store/loadout-file data-home)]
    (fs/create-dirs (fs/parent file))
    (spit file "{:shipyard/version 1 :loadouts [{:loadout/name \"missing fields\"}]}")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed loadout store"
                          (ig/init-key :shipyard.loadout/store {:data-home data-home})))
    (spit file "not edn {{{")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed loadout store"
                          (ig/init-key :shipyard.loadout/store {:data-home data-home})))))

(deftest invalid-write-leaves-the-last-known-good-store-intact
  (let [data-home (temp-dir)
        component (ig/init-key :shipyard.loadout/store {:data-home data-home})
        saved (loadout)]
    (store/replace! component [saved])
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/replace! component [(assoc saved :loadout/name "")])))
    (is (= [saved] (store/loadouts component)))
    (is (= [saved] (:loadouts (edn/read-string (slurp (store/loadout-file data-home))))))))
