(ns shipyard.integration.scan-test
  "Walks a real directory tree built for the test - never the model library."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.library.scan :as scan]))

(defn- touch [dir & files]
  (io/make-parents (io/file dir "x"))
  (.mkdirs (io/file dir))
  (doseq [f files] (spit (io/file dir f) "")))

(defn- fixture-tree
  "Mirrors the real library's shape, including its edge cases."
  ^java.io.File []
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "shipyard-lib-" (random-uuid)))]
    ;; a kitbash class
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "Hull")
           "unsupported.stl" "unsupported-pitted.stl" "supported.stl")
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "Classic Ram Prow")
           "unsupported.stl" "supported.stl")
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "weapons" "Lance Battery")
           "unsupported.stl")
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "weapons" "turrets" "Lance Turret")
           "unsupported.stl")
    ;; supported-only: catalogued, not dropped
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "Voss Nova Prow")
           "supported.stl")
    ;; pitted-only: catalogued, but never previewed as recessed geometry
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "Pitted Only Prow")
           "unsupported-pitted.stl")
    ;; ordinance and terrain come from the class segment
    (touch (io/file root "Human Navy Fleet Bundle" "ordinance" "Human Assault Boat Tall")
           "unsupported.stl")
    (touch (io/file root "Ork Fleet Bundle" "Terrain" "Orksteroid 1")
           "unsupported.stl")
    ;; a single-ship bundle - no class segment
    (touch (io/file root "IV - The Bloody Iron" "Bloody Iron Forward hull")
           "unsupported.stl")
    ;; other/ must be skipped whole, even though it contains files
    (touch (io/file root "Human Navy Fleet Bundle" "Cruiser" "other")
           "LYS_Something.lys" "README.txt")
    (touch (io/file root "Human Navy Fleet Bundle" "other" "Decoy")
           "unsupported.stl")
    root))

(defn- by-id [parts] (into {} (map (juxt :part/id identity) parts)))

(deftest scans-a-fixture-tree
  (let [parts (scan/scan! (fixture-tree))
        m     (by-id parts)]
    (is (= 9 (count parts)) "nine part folders; nothing under other/")
    (testing "other/ is skipped whole, including part folders nested inside it"
      (is (every? #(not (re-find #"/other/" (:part/id %))) parts)))

    (testing "path decomposition"
      (let [p (m "Human Navy Fleet Bundle/Cruiser/Hull")]
        (is (= "Human Navy Fleet Bundle" (:part/bundle p)))
        (is (= "Cruiser" (:part/class p)))
        (is (= "Hull" (:part/name p)))
        (is (false? (:part/weapons? p)))))

    (testing "a weapons/ segment is recorded and drives the role"
      (let [p (m "Human Navy Fleet Bundle/Cruiser/weapons/Lance Battery")]
        (is (true? (:part/weapons? p)))
        (is (= :weapon (:part/role-hint p)))
        (is (= :class (:part/role-source p)))))

    (testing "a turrets/ directory makes turret-ness a fact"
      (let [p (m "Human Navy Fleet Bundle/Cruiser/weapons/turrets/Lance Turret")]
        (is (true? (:part/turrets? p)))
        (is (= :turret (:part/role-hint p)))
        (is (= :class (:part/role-source p)))
        (is (= "Cruiser" (:part/class p))
            "turrets/ must not be mistaken for the class segment")))

    (testing "single-ship bundles have no class segment"
      (let [p (m "IV - The Bloody Iron/Bloody Iron Forward hull")]
        (is (nil? (:part/class p)))
        (is (= :hull-section (:part/role-hint p)))))

    (testing "variant discovery and preference"
      (let [p (m "Human Navy Fleet Bundle/Cruiser/Hull")]
        (is (= #{:supported :unsupported :unsupported-pitted} (:part/variants p)))
        (is (= :unsupported (:part/source p)) "plain unsupported wins")))

    (testing "supported-only parts are catalogued and flagged, never dropped"
      (let [p (m "Human Navy Fleet Bundle/Cruiser/Voss Nova Prow")]
        (is (some? p) "the part must still appear in the library")
        (is (= #{:supported} (:part/variants p)))
        (is (nil? (:part/source p)))
        (is (false? (:part/renderable p)))))
    (testing "pitted-only parts are catalogued but never displayed"
      (let [p (m "Human Navy Fleet Bundle/Cruiser/Pitted Only Prow")]
        (is (= #{:unsupported-pitted} (:part/variants p)))
        (is (nil? (:part/source p)))
        (is (false? (:part/renderable p)))))))

(deftest missing-root-is-not-an-error
  (testing "a fresh install points somewhere that does not exist yet"
    (is (nil? (scan/scan! (io/file "/no/such/library"))))))

(deftest results-are-stable
  (let [root (fixture-tree)]
    (is (= (scan/scan! root) (scan/scan! root)) "scanning twice gives the same records")
    (is (= (map :part/id (scan/scan! root)) (sort (map :part/id (scan/scan! root))))
        "sorted by id, so catalog ingestion is deterministic")))
