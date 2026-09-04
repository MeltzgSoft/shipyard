(ns shipyard.integration.catalog-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [shipyard.catalog.db :as db]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.library.scan :as scan]
            [shipyard.part.orientation :as orientation])
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
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "weapons" "turrets" "Lance Turret") "unsupported.stl")
    (touch (io/file root "Human Navy Fleet Bundle" "Battleship" "Battleship Hull") "unsupported.stl")
    (touch (io/file root "Ork Fleet Bundle" "Cruiser" "Battle Krooza Hull") "unsupported.stl")
    root))

(def ^:private a-mount
  {:mount/id :prow :mount/kind :socket :mount/accepts #{:prow}
   :mount/pos [0.0 0.0 86.77] :mount/axis [0.0 0.0 1.0] :mount/roll [0.0 1.0 0.0]
   :mount/origin :picked})

(defn- catalog [root]
  {:state (atom {:conn (db/ingest (scan/scan root) root) :root root})})

(deftest schema-and-ingest
  (let [root (fixture-tree)
        conn (db/conn (catalog root))]
    (is (= 6 (count (db/browse @conn {}))))
    (testing ":part/id is an identity, so re-transacting updates rather than duplicating"
      (d/transact! conn [{:part/id "Human Navy Fleet Bundle/Cruiser/Hull" :part/tris 42}])
      (is (= 6 (count (db/browse @conn {}))))
      (is (= 42 (:part/tris (db/part @conn "Human Navy Fleet Bundle/Cruiser/Hull")))))))

(deftest holds-metadata-only
  (testing "a vertex buffer in the catalog would balloon the heap and make the DB
            non-derivable, so assert no geometry attribute exists anywhere"
    (let [root (fixture-tree)
          conn (db/conn (catalog root))
          attrs (set (d/q '[:find [?a ...] :where [_ ?a]] @conn))]
      (is (empty? (set/intersection attrs db/geometry-keys)))
      (is (every? #(not (re-find #"position|normal|vertex|geometry|indices" (name %))) attrs)))))

(deftest turret-metadata-survives-ingest
  (let [root (fixture-tree)
        conn (db/conn (catalog root))
        db @conn]
    (testing "directory facts reach the catalog rather than being dropped"
      (let [t (db/part db "Human Navy Fleet Bundle/Cruiser/weapons/turrets/Lance Turret")]
        (is (= :turret (:part/role-hint t)))
        (is (true? (:part/turrets? t)))
        (is (not (:part/accepts-turrets? t)) "a turret does not accept a turret")))
    (testing "the mount wizard's shortlist is queryable"
      (let [ids (set (map :part/id (db/browse db {:accepts-turrets? true})))]
        (is (contains? ids "Human Navy Fleet Bundle/Cruiser/weapons/Lance Battery")
            "a battery carries the holes")
        (is (contains? ids "Human Navy Fleet Bundle/Cruiser/Hull")
            "so does a cruiser hull")
        (is (not (contains? ids "Human Navy Fleet Bundle/Cruiser/Classic Ram Prow")))))))

(deftest browse-queries
  (let [root (fixture-tree)
        conn (db/conn (catalog root))
        db @conn]
    (is (= ["Human Navy Fleet Bundle" "Ork Fleet Bundle"] (db/bundles db)))
    (is (= ["Battleship" "Cruiser"] (db/classes db "Human Navy Fleet Bundle")))
    (is (= 4 (count (db/browse db {:bundle "Human Navy Fleet Bundle" :class "Cruiser"}))))
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
      (let [conn (db/conn (catalog root))]
        (is (= 6 (count (db/browse @conn {}))))))))

(deftest write-through-is-file-first
  (let [root (fixture-tree)
        cat  (catalog root)
        id   "Human Navy Fleet Bundle/Cruiser/Hull"]
    (db/save-mounts! cat id [a-mount])
    (is (= [a-mount] (:mounts (sidecar/read-sidecar root id))))
    (is (= 1 (count (:part/mounts (db/part @(db/conn cat) id)))))

    (testing "a failing transact still leaves the sidecar on disk"
      (let [broken {:state (atom {:conn (d/create-conn db/schema) :root root})}]   ; part not in this DB
        (try (db/save-mounts! broken "Ork Fleet Bundle/Cruiser/Battle Krooza Hull" [a-mount])
             (catch Exception _ nil))
        (is (= [a-mount] (:mounts (sidecar/read-sidecar
                                   root "Ork Fleet Bundle/Cruiser/Battle Krooza Hull")))
            "the durable write happened before the index write")))))

(deftest authoring-writes-mounts-and-manual-role
  (let [root (fixture-tree)
        cat  (catalog root)
        id   "Human Navy Fleet Bundle/Cruiser/Classic Ram Prow"]
    (sidecar/write-sidecar! root id {:kept "yes"})
    (db/save-authoring! cat id {:mounts [a-mount] :part-role :weapon})
    (let [sidecar (sidecar/read-sidecar root id)
          part (db/part @(db/conn cat) id)]
      (is (= "yes" (:kept sidecar)) "unknown sidecar fields survive")
      (is (= :weapon (:part/role sidecar)))
      (is (= :weapon (:part/role-hint part)))
      (is (= :manual (:part/role-source part)))
      (is (= 1 (count (:part/mounts part)))))
    (testing "replace and delete update the derived component refs rather than accumulating"
      (db/save-authoring! cat id {:mounts [(assoc a-mount :mount/id :weapon-back)]
                                  :part-role :weapon})
      (is (= [:weapon-back] (mapv :mount/id (:part/mounts (db/part @(db/conn cat) id)))))
      (db/save-authoring! cat id {:mounts []})
      (is (empty? (:part/mounts (db/part @(db/conn cat) id)))))
    (testing "restart re-ingests the manual role from the sidecar"
      (let [part (db/part @(db/conn (catalog root)) id)]
        (is (= :weapon (:part/role-hint part)))
        (is (= :manual (:part/role-source part)))))))

(deftest mirrored-mounts-round-trip-through-sidecars
  (let [root (fixture-tree)
        cat  (catalog root)
        id   "Human Navy Fleet Bundle/Cruiser/Hull"
        mirrored (assoc a-mount
                        :mount/id :starboard-prow
                        :mount/pos [-1.0 0.0 86.77]
                        :mount/axis [-1.0 0.0 0.0]
                        :mount/roll [0.0 0.0 1.0]
                        :mount/origin :mirrored)
        mounts [a-mount mirrored]]
    (db/save-authoring! cat id {:mounts mounts :part-role :hull})
    (is (= mounts (:mounts (sidecar/read-sidecar root id))))
    (let [part (db/part @(db/conn (catalog root)) id)]
      (is (= (mapv #(select-keys % [:mount/id :mount/pos :mount/axis :mount/roll :mount/origin])
                   mounts)
             (mapv #(select-keys % [:mount/id :mount/pos :mount/axis :mount/roll :mount/origin])
                   (:part/mounts part))))
      (is (= :manual (:part/role-source part))))))

(deftest part-orientation-round-trips-through-sidecars
  (let [root (fixture-tree)
        cat (catalog root)
        id "Human Navy Fleet Bundle/Cruiser/Hull"
        q (orientation/from-euler-degrees 90 0 0)]
    (sidecar/write-sidecar! root id {:mounts [a-mount] :part/role :hull})
    (db/save-part-orientation! cat id q)
    (let [sidecar (sidecar/read-sidecar root id)
          part (db/part @(db/conn cat) id)]
      (is (= q (:part/orientation sidecar)))
      (is (= q (:part/orientation part)))
      (is (= [a-mount] (:mounts sidecar)) "orientation does not rewrite mounts")
      (is (= :hull (:part/role sidecar)) "orientation does not rewrite part metadata"))
    (testing "restart re-ingests orientation from the sidecar"
      (is (= q (:part/orientation (db/part @(db/conn (catalog root)) id)))))
    (testing "parts without orientation remain identity at the behavior boundary"
      (is (= orientation/identity-quaternion
             (orientation/orientation-of
              (:part/orientation
               (db/part @(db/conn cat)
                        "Human Navy Fleet Bundle/Cruiser/Classic Ram Prow"))))))))

(deftest authoring-is-file-first-when-the-index-write-fails
  (let [root (fixture-tree)
        cat  (catalog root)
        id   "Human Navy Fleet Bundle/Cruiser/Hull"]
    (with-redefs [d/transact! (fn [& _] (throw (ex-info "index failed" {})))]
      (try
        (db/save-authoring! cat id {:mounts [a-mount] :part-role :hull})
        (catch Exception _ nil)))
    (let [sidecar (sidecar/read-sidecar root id)]
      (is (= [a-mount] (:mounts sidecar))
          "the durable mount write happened before the index write")
      (is (= :hull (:part/role sidecar))
          "the durable role write happened before the index write"))))

(deftest db-is-genuinely-derived
  (testing "deleting the whole DB and re-ingesting reproduces identical state"
    (let [root (fixture-tree)
          id   "Human Navy Fleet Bundle/Cruiser/Hull"
          c1   (catalog root)]
      (db/save-mounts! c1 id [a-mount])
      (let [before (db/browse @(db/conn c1) {})
            after  (db/browse @(db/conn (catalog root)) {})]
        (is (= (map :part/id before) (map :part/id after)))
        (is (= (count (:part/mounts (db/part @(db/conn (catalog root)) id))) 1)
            "mounts come back from the sidecar, not from the discarded DB")))))
