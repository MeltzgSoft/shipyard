(ns shipyard.integration.canary-test
  "The canary against a library built to contain every anomaly it reports.

  Fixtures cannot prove the canary finds the unknown - that is exactly why the
  canary exists and why it runs against the real 19 GB collection. What they can
  prove is that each check fires on a case that should fire it, that one bad
  file does not stop the walk, and that the whole run leaves the library
  byte-for-byte unchanged."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [digest]
            [shipyard.canary :as canary]
            [shipyard.fixtures :as f]
            [shipyard.library.scan :as scan])
  (:import [java.io File]))

(defn- temp-dir ^File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- write! [dir file ^bytes bytes]
  (let [f (io/file dir file)]
    (io/make-parents f)
    (with-open [o (io/output-stream f)] (.write o bytes))
    f))

(def ^:private flat-quad
  "Two coplanar triangles. Zero volume, and zero thickness in z."
  [[[0.0 0.0 0.0] [1.0 0.0 0.0] [1.0 1.0 0.0]]
   [[0.0 0.0 0.0] [1.0 1.0 0.0] [0.0 1.0 0.0]]])

(defn- library-tree ^File []
  (let [root (temp-dir "shipyard-canary")]
    (write! (io/file root "Bundle/Cruiser/Good Hull") "unsupported.stl"
            (f/->binary-stl (f/uv-sphere 1.0 10 20)))
    ;; The real library's one ASCII file, in miniature: its binary header parses
    ;; as a preposterous triangle count, so `84 + 50n` does not hold.
    (write! (io/file root "Bundle/Cruiser/Ascii Prow") "unsupported.stl"
            (f/->ascii-stl (f/cube 2.0) "\r\n"))
    (write! (io/file root "Bundle/Cruiser/Supported Only") "supported.stl"
            (f/->binary-stl (f/cube 1.0)))
    (write! (io/file root "Bundle/Cruiser/Empty") "unsupported.stl"
            (f/->binary-stl []))
    (write! (io/file root "Bundle/Cruiser/Flat Plate") "unsupported.stl"
            (f/->binary-stl flat-quad))
    (write! (io/file root "Bundle/Cruiser/Corrupt") "unsupported.stl"
            (.getBytes "this is not an STL at all, not even nearly" "US-ASCII"))
    root))

(defn- fingerprint
  "Every file's path, size and content hash. A weaker check - mtime, or a count
  of files - would miss a rewrite that happened to preserve length."
  [root]
  (into (sorted-map)
        (for [f (fs/glob root "**") :when (fs/regular-file? f)]
          [(str (fs/relativize root f))
           [(fs/size f) (digest/sha-256 (fs/file f))]])))

(defn- report [root]
  (canary/run {:root (str root) :parts (scan/scan root)
               :crease-deg 35 :lod-tiers [1.0 0.25 0.05]}))

(defn- kinds [report] (set (map :kind (:findings report))))

(defn- of-kind [report kind]
  (filter #(= kind (:kind %)) (:findings report)))

(deftest reports-every-anomaly-it-claims-to
  (let [root (library-tree)
        r    (report root)]
    (is (= 6 (:parts r)))
    (testing "parts with no renderable variant - 73 such folders in the real library"
      (is (= ["Bundle/Cruiser/Supported Only"]
             (map :part/id (of-kind r :no-renderable-variant)))))
    (testing "the 84 + 50n check, which is how an ASCII STL announces itself"
      (let [[hit :as all] (of-kind r :header-size-mismatch)]
        (is (= 1 (count all)))
        (is (= "Bundle/Cruiser/Ascii Prow" (:part/id hit)))
        (is (not= (:size hit) (+ 84 (* 50 (:declared hit)))))))
    (testing "empty meshes"
      (is (= ["Bundle/Cruiser/Empty"] (map :part/id (of-kind r :empty-mesh)))))
    (testing "zero-volume meshes"
      (let [[hit] (of-kind r :zero-volume)]
        (is (= "Bundle/Cruiser/Flat Plate" (:part/id hit)))
        (is (some #(< (double %) 1.0e-4) (:extents hit)))))
    (testing "and a part whose preprocessing throws, with the path"
      (let [[hit] (of-kind r :preprocess-failed)]
        (is (= "Bundle/Cruiser/Corrupt" (:part/id hit)))
        (is (string? (:message hit)))
        (is (fs/regular-file? (:file hit)))))
    (testing "the good hull produces no findings at all"
      (is (empty? (filter #(= "Bundle/Cruiser/Good Hull" (:part/id %)) (:findings r)))))))

(deftest one-bad-file-does-not-stop-the-walk
  (testing "the whole point is the list, so every part is examined even when the
            one before it threw"
    (let [r (report (library-tree))]
      (is (contains? (kinds r) :preprocess-failed))
      (is (contains? (kinds r) :empty-mesh))
      (is (>= (count (:findings r)) 5)))))

(deftest the-canary-never-writes-to-the-library
  (testing "read-only is the one property that must hold on 19 GB of purchased
            assets, so it is checked by content, not by intent"
    (let [root   (library-tree)
          before (fingerprint root)]
      (report root)
      (is (= before (fingerprint root)))
      (is (= 6 (count before)) "and nothing was added or removed"))))

(deftest the-report-is-machine-readable
  (testing "so runs can be diffed after acquiring a new bundle"
    (let [root (library-tree)
          r    (report root)
          out  (io/file (temp-dir "shipyard-canary-out") "canary.edn")]
      (canary/write-report! out r)
      (let [read-back (edn/read-string (slurp out))]
        (is (= (:totals r) (:totals read-back)))
        (is (= (set (map :part/id (:findings r)))
               (set (map :part/id (:findings read-back)))))
        (is (string? (:ran-at read-back)))))))

(deftest the-summary-names-every-check-even-at-zero
  (testing "a missing row is not information; `0 header-size-mismatch` is"
    (let [rows (canary/summary-rows {:totals {}})]
      (is (= (set (map name (keys canary/kind-labels)))
             (set (map #(subs (get % "kind") 1) rows))))
      (is (every? #(= 0 (get % "count")) rows)))))
