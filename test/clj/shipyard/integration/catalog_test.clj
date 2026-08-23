(ns shipyard.integration.catalog-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [shipyard.catalog.db :as db]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.library.scan :as scan])
  (:import [java.io File]))

(defn- touch [dir & files]
  (.mkdirs (io/file dir))
  (doseq [f files] (spit (io/file dir f) "")))

(defn- fixture-tree ^File []
  (let [root (io/file (System/getProperty "java.io.tmpdir") (str "sy-cat-" (random-uuid)))]
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "Hull")
           "unsupported.stl" "unsupported-pitted.stl")
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "Classic Ram Prow") "unsupported.stl")
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "weapons" "Lance Battery") "unsupported.stl")
    (touch (io/file root "Human Navy Fleet Bundle" "Battleship" "Battleship Hull") "unsupported.stl")
    (touch (io/file root "Ork Fleet Bundle" "Cruiser" "Battle Krooza Hull") "unsupported.stl")
    root))

(def ^:private a-mount
  {:mount/id :prow :mount/kind :socket :mount/accepts #{:prow}
   :mount/pos [0.0 0.0 86.77] :mount/axis [0.0 0.0 1.0] :mount/roll [0.0 1.0 0.0]
   :mount/origin :picked})

(defn- catalog [root]
  {:conn (db/ingest (scan/scan root) root) :root root})

(deftest schema-and-ingest
  (let [root (fixture-tree)
        {:keys [conn]} (catalog root)]
    (is (= 5 (count (db/browse @conn {}))))
    (testing ":part/id is an identity, so re-transacting updates rather than duplicating"
      (d/transact! conn [{:part/id "Human Navy Fleet Bundle/Cruiser/Hull" :part/tris 42}])
      (is (= 5 (count (db/browse @conn {}))))
      (is (= 42 (:part/tris (db/part @conn "Human Navy Fleet Bundle/Cruiser/Hull")))))))

(deftest holds-metadata-only
  (testing "a vertex buffer in the catalog would balloon the heap and make the DB
            non-derivable, so assert no geometry attribute exists anywhere"
    (let [root (fixture-tree)
          {:keys [conn]} (catalog root)
          attrs (set (d/q '[:find [?a ...] :where [_ ?a]] @conn))]
      (is (empty? (set/intersection attrs db/geometry-keys)))
      (is (every? #(not (re-find #"position|normal|vertex|geometry|indices" (name %))) attrs)))))

(deftest browse-queries
  (let [root (fixture-tree)
        {:keys [conn]} (catalog root)
        db @conn]
    (is (= ["Human Navy Fleet Bundle" "Ork Fleet Bundle"] (db/bundles db)))
    (is (= ["Battleship" "Cruiser"] (db/classes db "Human Navy Fleet Bundle")))
    (is (= 3 (count (db/browse db {:bundle "Human Navy Fleet Bundle" :class "Cruiser"}))))
    (is (= 1 (count (db/browse db {:role :weapon}))))
    (is (= 3 (count (db/browse db {:role :hull}))))
    (testing "free-text search on the name"
      (is (= 1 (count (db/browse db {:q "krooza"}))))
      (is (= 1 (count (db/browse db {:q "KROOZA"})))))
    (testing "regex metacharacters in a query are literal, not a syntax error"
      (is (empty? (db/browse db {:q "hull("}))))))

(deftest sidecar-roundtrip
  (let [root (fixture-tree)
        id   "Human Navy Fleet Bundle/Cruiser/Hull"]
    (sidecar/write-sidecar! root id {:mounts [a-mount]})
    (let [back (sidecar/read-sidecar root id)]
      (is (= [a-mount] (:mounts back)))
      (is (= sidecar/format-version (:shipyard/version back))))
    (testing "a part with no sidecar reads as nil, not an error"
      (is (nil? (sidecar/read-sidecar root "Ork Fleet Bundle/Cruiser/Battle Krooza Hull"))))))

(deftest malformed-sidecar-fails-loudly
  (let [root (fixture-tree)
        id   "Human Navy Fleet Bundle/Cruiser/Hull"]
    (spit (sidecar/sidecar-file root id) "{:mounts [{{{")
    (testing "it must NEVER degrade to 'this part has no mounts' - that is
              indistinguishable from an unauthored part, so a syntax error would
              silently discard work"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed sidecar"
                            (sidecar/read-sidecar root id))))
    (testing "but one bad file must not make the whole library invisible"
      (let [{:keys [conn]} (catalog root)]
        (is (= 5 (count (db/browse @conn {}))))))))

(deftest write-through-is-file-first
  (let [root (fixture-tree)
        cat  (catalog root)
        id   "Human Navy Fleet Bundle/Cruiser/Hull"]
    (db/save-mounts! cat id [a-mount])
    (is (= [a-mount] (:mounts (sidecar/read-sidecar root id))))
    (is (= 1 (count (:part/mounts (db/part @(:conn cat) id)))))

    (testing "a failing transact still leaves the sidecar on disk"
      (let [broken {:conn (d/create-conn db/schema) :root root}]   ; part not in this DB
        (try (db/save-mounts! broken "Ork Fleet Bundle/Cruiser/Battle Krooza Hull" [a-mount])
             (catch Exception _ nil))
        (is (= [a-mount] (:mounts (sidecar/read-sidecar
                                   root "Ork Fleet Bundle/Cruiser/Battle Krooza Hull")))
            "the durable write happened before the index write")))))

(deftest db-is-genuinely-derived
  (testing "deleting the whole DB and re-ingesting reproduces identical state"
    (let [root (fixture-tree)
          id   "Human Navy Fleet Bundle/Cruiser/Hull"
          c1   (catalog root)]
      (db/save-mounts! c1 id [a-mount])
      (let [before (db/browse @(:conn c1) {})
            after  (db/browse @(:conn (catalog root)) {})]
        (is (= (map :part/id before) (map :part/id after)))
        (is (= (count (:part/mounts (db/part @(:conn (catalog root)) id))) 1)
            "mounts come back from the sidecar, not from the discarded DB")))))
