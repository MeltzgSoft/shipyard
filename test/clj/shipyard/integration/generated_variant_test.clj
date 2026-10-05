(ns shipyard.integration.generated-variant-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.part-browser.variants :as variants]
            [shipyard.pitting.db :as pitting]
            [shipyard.variant-availability-fixture :as parts]))

(defn- context! [started]
  (let [sys (:system started) lib (:shipyard.library/index sys) cat (:shipyard.catalog/db sys)
        source (fs/file (:root started) parts/plain "unsupported.stl")
        prepared (cache/ensure! (:shipyard.mesh/cache sys) source)
        key (index/record-mesh-key! lib parts/plain (:mesh-key prepared) (:tris prepared))]
    {:deps {:catalog cat :library lib} :source source :target (pitting/target-file source)
     :mount {:mount/id :top :mount/kind :socket :mount/accepts #{:weapon} :mount/capacity 1
             :mount/pos [0.0 0.0 0.5] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]
             :mount/outline [[[-0.5 -0.5 0.5] [0.5 -0.5 0.5] [0.5 0.5 0.5] [-0.5 0.5 0.5]]]
             :mount/cut {:kind :pit :depth 0.1 :diameter 0.2 :mesh-key key}}}))

(defn- fail-inventory-write [transact]
  (fn [conn tx]
    (when (some #(= :unsupported-pitted (get-in % [:part/sources 0 :source/variant])) tx)
      (throw (ex-info "inventory write failed" {})))
    (transact conn tx)))

(deftest generated-files-update-inventory-and-filters-without-rescan
  (let [started (fixture/start! false parts/build! (fn [_]))
        {:keys [deps mount source target]} (context! started)
        cat (:catalog deps) lib (:library deps)
        part #(:part (catalog/part-context! cat parts/plain))
        before @(:state lib)
        others (dissoc (:parts (catalog/listing! cat)) parts/plain)
        filtered (fn [choice]
                   (mapv second (re-seq #"data-part-row=\"([^\"]+)\""
                                        (:body ((:handler started) (mock/request :get "/orient/parts"
                                                                                 {"has-unsupported" "available" "has-pitted" choice}))))))]
    (try
      (is (= [parts/plain] (filtered "missing")))
      (pitting/save! deps parts/plain [mount] (:part/revision (part)))
      (is (= [parts/all parts/cut parts/plain] (filtered "available")))
      (is (empty? (filtered "missing")))
      (is (contains? (set (:part/variants (catalog/summary! cat parts/plain))) :unsupported-pitted))
      (is (= (:entries before) (:entries @(:state lib))) "the original authoring cache remains current")
      (is (= others (dissoc (:parts (catalog/listing! cat)) parts/plain)))
      (let [file #(first (filter (fn [f] (and (= parts/plain (:owner f)) (= :unsupported-pitted (:variant f))))
                                 (variants/files! cat)))
            identity (:eid (file))]
        (is (= (str (fs/path parts/plain "unsupported-pitted.stl")) (:path (file))))
        (is (= (fs/size target) (:size (file))))
        (is (= (fs/file-time->millis (fs/last-modified-time target)) (:mtime (file))))
        (let [recess (assoc mount :mount/cut {:kind :recess :depth 0.1 :border 0.1
                                              :mesh-key (get-in mount [:mount/cut :mesh-key])})]
          (pitting/save! deps parts/plain [recess] (:part/revision (part)))
          (is (= identity (:eid (file))) "regeneration retains the source entity")
          (is (= (fs/size target) (:size (file))))
          (is (= (fs/file-time->millis (fs/last-modified-time target)) (:mtime (file))))
          (pitting/save! deps parts/plain [] (:part/revision (part)))
          (is (= (seq (fs/read-all-bytes source)) (seq (fs/read-all-bytes target))))
          (is (= [parts/all parts/cut parts/plain] (filtered "available")))
          (is (= identity (:eid (file)))))
        (catalog/reingest! cat (index/parts! lib) (index/root! lib))
        (is (= identity (:eid (file))) "catalog reopening retains the observed variant"))
      (finally (fixture/stop! started)))))

(deftest inventory-transaction-failure-restores-output-and-definitions
  (let [started (fixture/start! false parts/build! (fn [_]))
        {:keys [deps mount target]} (context! started)
        cat (:catalog deps) lib (:library deps)
        part #(:part (catalog/part-context! cat parts/plain))
        transact d/transact!]
    (try
      (testing "a failed first publication leaves no available cut variant"
        (let [saved (part) inventory @(:state lib)]
          (with-redefs [d/transact! (fail-inventory-write transact)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inventory write failed"
                                  (pitting/save! deps parts/plain [mount] (:part/revision saved)))))
          (is (= saved (part)))
          (is (= inventory @(:state lib)))
          (is (not (.exists target)))))
      (pitting/save! deps parts/plain [mount] (:part/revision (part)))
      (testing "a failed regeneration restores the prior bytes and file stamp"
        (let [saved (part) files (variants/files! cat) inventory @(:state lib)
              bytes (fs/read-all-bytes target) mtime (fs/last-modified-time target)]
          (with-redefs [d/transact! (fail-inventory-write transact)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inventory write failed"
                                  (pitting/save! deps parts/plain [(assoc-in mount [:mount/cut :depth] 0.2)]
                                                 (:part/revision saved)))))
          (is (= saved (part)))
          (is (= files (variants/files! cat)))
          (is (= inventory @(:state lib)))
          (is (= (seq bytes) (seq (fs/read-all-bytes target))))
          (is (= mtime (fs/last-modified-time target)))))
      (is (empty? (fs/glob (.getParentFile target) ".shipyard-pitted-*")))
      (finally (fixture/stop! started)))))
