(ns shipyard.integration.cache-test
  "Real filesystem, real natives. This is the suite Windows CI exists for:
  path separators, file locking and Files.move atomicity (§10.2)."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.store.db :as store]
            [shipyard.store.scan-index :as scan-index])
  (:import [java.io File]
           [java.util.concurrent Executors TimeUnit]))

(defn- temp-dir ^File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- test-cache
  ([] (test-cache 64000000))
  ([cap]
   {:dir (temp-dir "shipyard-cache") :crease-deg 35 :lod-tiers [1.0 0.25 0.05]
    :cap-bytes cap :inflight (atom {}) :files-lock (Object.)}))

(defn- write-stl ^File [dir n]
  (let [f (io/file dir "unsupported.stl")]
    (io/make-parents f)
    (with-open [o (io/output-stream f)]
      (.write o ^bytes (f/->binary-stl (f/uv-sphere 1.0 n (* 2 n)))))
    f))

(deftest preprocesses-once-and-caches
  (let [c (test-cache), src (write-stl (temp-dir "shipyard-src") 12)]
    (let [a (cache/ensure! c src)]
      (is (:mesh-key a))
      (is (= 3 (:tiers a)) "all three tiers written")
      (is (.isFile (cache/tier-file c (:mesh-key a) 0)))
      (is (.isFile (cache/tier-file c (:mesh-key a) 2))))
    (testing "a second call is a hit, not a rebuild"
      (is (:cached (cache/ensure! c src))))))

(deftest touching-the-source-invalidates
  (let [src (write-stl (temp-dir "shipyard-src") 8)
        before (cache/sha256! src)]
    (testing "content hash changes when the file changes, so the key changes"
      (with-open [o (io/output-stream src :append true)] (.write o (byte-array 4)))
      (is (not= before (cache/sha256! src))))))

(deftest index-freshness-tracks-mtime-and-size
  (let [src (write-stl (temp-dir "shipyard-src") 6)
        e   {:mtime (.lastModified src) :size (.length src) :mesh-key "abc"}]
    (is (index/fresh-source?! e src))
    (testing "a size change invalidates"
      (is (not (index/fresh-source?! (assoc e :size 1) src))))
    (testing "an mtime change invalidates - this is what re-pitting a hull does"
      (is (not (index/fresh-source?! (assoc e :mtime 1) src))))
    (is (not (index/fresh-source?! nil src)))))

(deftest index-roundtrips-through-the-shared-database
  (let [dir (fs/create-temp-dir {:prefix "shipyard-index-db-"})
        entries {"a/b" {:mtime 1 :size 2 :mesh-key "k" :tris 12
                        :escort-analysis {:volume 42.0}}}]
    (try
      (let [database (store/open! dir)]
        (try
          (scan-index/replace! database "/lib" entries)
          (is (= entries (scan-index/entries! database "/lib")))
          (testing "the same relative part path in another library is independent"
            (is (= {} (scan-index/entries! database "/somewhere-else"))))
          (finally (store/close! database))))
      (let [database (store/open! dir)]
        (try
          (testing "reopening the database retains each derived field"
            (is (= entries (scan-index/entries! database "/lib"))))
          (testing "equivalent normalized roots share one index identity"
            (is (= entries (scan-index/entries! database "/lib/../lib"))))
          (finally (store/close! database))))
      (finally (fs/delete-tree dir)))))

(deftest concurrent-requests-return-the-same-mesh
  (let [c (test-cache), src (write-stl (temp-dir "shipyard-src") 10)
        pool (Executors/newFixedThreadPool 8)
        results (atom [])]
    (dotimes [_ 8]
      (.submit pool ^Runnable (fn [] (swap! results conj (cache/ensure! c src)))))
    (.shutdown pool)
    (.awaitTermination pool 120 TimeUnit/SECONDS)
    (is (= 8 (count @results)))
    (is (= 1 (count (distinct (map :mesh-key @results))))
        "every caller agrees on the key")
    ;; Late callers may legitimately observe a warm hit. The admission-barrier
    ;; regression separately proves that overlapping work shares one job.
    (is (every? #(or (:cached %) (= 3 (:tiers %))) @results))))

(deftest eviction-enforces-the-cap
  (let [c (test-cache 1)                       ; cap of one byte: evict everything
        src (write-stl (temp-dir "shipyard-src") 10)]
    (cache/ensure! c src)
    (cache/evict! c)
    (is (< (cache/cache-size! c) 1000)
        "an aggressive cap must actually drop tiers, not just log about it")))

(deftest eviction-is-safe-because-everything-regenerates
  (let [c (test-cache), src (write-stl (temp-dir "shipyard-src") 10)
        a (cache/ensure! c src)]
    (doseq [^File f (file-seq (:dir c))] (when (.isFile f) (.delete f)))
    (testing "wiping the cache costs recomputation and nothing else"
      (let [b (cache/ensure! c src)]
        (is (= (:mesh-key a) (:mesh-key b)))
        (is (.isFile (cache/tier-file c (:mesh-key b) 0)))))))
