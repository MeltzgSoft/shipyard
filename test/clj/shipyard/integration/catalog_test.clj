(ns shipyard.integration.catalog-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.regions.model :as regions]
            [shipyard.store.db :as store]))

(deftest authored-data-survives-scan-and-durable-reopen
  (let [started (fixture/start!) sys (:system started) cat (:shipyard.catalog/db sys)
        root (:root started) id (:weapon fixture/ids) read! #(catalog/part (catalog/snapshot! cat) id)]
    (try
      (let [uid (:part/uid (read!)) before (slurp (str (fs/path root id "shipyard.edn")))]
        (catalog/save-part-role! cat id :bridge)
        (catalog/save-part-orientation! cat id [0.0 0.0 0.0 1.0])
        (catalog/save-mounts! cat id [fixture/plug])
        (is (= before (slurp (str (fs/path root id "shipyard.edn")))) "Legacy input is never rewritten")
        (fs/delete (fs/path root id "shipyard.edn"))
        (catalog/reingest! cat (index/parts! (:shipyard.library/index sys)) (str root))
        (let [part (catalog/part (persisted/catalog! cat) id)]
          (is (= uid (:part/uid part)))
          (is (= :bridge (:part/role-hint part)))
          (is (= :manual (:part/role-source part)))
          (is (= [fixture/plug] (:part/mounts part)))
          (is (= [0.0 0.0 0.0 1.0] (:part/orientation part)))))
      (testing "failed multi-entity transactions publish nothing"
        (let [before (catalog/snapshot! cat) db (:shipyard.store/db sys)]
          (is (thrown? Exception
                       (store/write! db (fn [conn]
                                          (d/transact! conn [{:part/uid (:part/uid (read!)) :part/role-override :antenna}])
                                          (d/transact! conn [{:part/uid (:part/uid (read!)) :part/revision "invalid"}])))))
          (is (= before (catalog/snapshot! cat)))
          (is (= before (persisted/catalog! cat)))))
      (finally (fixture/stop! started)))))

(deftest missing-source-is-not-deleted-authorship
  (let [started (fixture/start!) sys (:system started) cat (:shipyard.catalog/db sys)
        id (:weapon fixture/ids) before (catalog/part (catalog/snapshot! cat) id)]
    (try
      (catalog/reingest! cat [] (str (:root started)))
      (is (nil? (catalog/part (catalog/snapshot! cat) id)))
      (is (nil? (:part (catalog/part-context! cat id))))
      (is (= (:part/uid before) (get-in (persisted/catalog! cat) [:parts id :part/uid])))
      (catalog/reingest! cat (index/parts! (:shipyard.library/index sys)) (str (:root started)))
      (is (= (:part/mounts before) (:part/mounts (catalog/part (catalog/snapshot! cat) id))))
      (finally (fixture/stop! started)))))

(deftest selected-part-context-includes-durable-regions-and-shared-layers
  (let [started (fixture/start!) cat (:shipyard.catalog/db (:system started))
        id (:weapon fixture/ids)]
    (try
      (catalog/save-regions! cat id (assoc (regions/empty-regions (apply str (repeat 64 "b")))
                                           :revision 1 :faces {(apply str (repeat 72 "a")) "Secondary"}))
      (let [snapshot (catalog/snapshot! cat) context (catalog/part-context! cat id)]
        (is (= (catalog/part snapshot id) (:part context)))
        (is (= (catalog/region-registry snapshot) (:registry context)))
        (is (= (:registry context) (catalog/region-registry! cat)))
        (is (seq (get-in context [:part :part/paint-regions :faces])))
        (is (nil? (:part (catalog/part-context! cat "missing")))))
      (finally (fixture/stop! started)))))
