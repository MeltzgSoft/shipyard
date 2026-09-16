(ns shipyard.integration.cache-concurrency-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.fixtures :as fixtures]
            [shipyard.mesh.cache :as cache]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]
           [java.util.concurrent Callable CountDownLatch Executors TimeUnit]))

(defn- with-cache! [run]
  (let [root (fs/create-temp-dir {:prefix "shipyard-concurrent-cache-"})
        c (ig/init-key :shipyard.mesh/cache {:cache-home (str root) :cap-bytes 64000000
                                             :crease-deg 35 :lod-tiers [1.0 0.25 0.05]})
        pool (Executors/newFixedThreadPool 8)]
    (try (run c root pool)
         (finally
           (.shutdownNow pool)
           (.awaitTermination pool 15 TimeUnit/SECONDS)
           (fs/delete-tree root)))))

(defn- source! [root name size]
  (let [file (fs/file root name)]
    (io/copy (fixtures/->binary-stl (fixtures/cube size)) file)
    file))

(defn- decode-tier! [c key tier]
  (wire/decode (Files/readAllBytes (fs/path (cache/tier-file c key tier)))))

(deftest identical-content-at-different-paths-shares-one-inflight-job
  (with-cache!
    (fn [c root pool]
      (let [sources (mapv #(source! root (str % ".stl") 1.0) (range 8))
            admitted (CountDownLatch. 8) release (CountDownLatch. 1)]
        ;; Observe admission without replacing parsing, preprocessing or I/O.
        ;; Hold every caller before deref until all eight claims overlap.
        (add-watch (:inflight c) ::admission
                   (fn [_ _ _ after]
                     (when (and (seq after) (pos? (.getCount admitted)))
                       (.countDown admitted)
                       (when-not (.await release 10 TimeUnit/SECONDS)
                         (throw (ex-info "Admission barrier timed out" {}))))))
        (try
          (let [calls (mapv #(.submit pool ^Callable (fn [] (cache/ensure! c %))) sources)]
            (is (.await admitted 10 TimeUnit/SECONDS))
            (is (= 1 (count @(:inflight c))) "distinct paths must share the same mesh claim")
            (.countDown release)
            (let [results (mapv #(.get % 30 TimeUnit/SECONDS) calls)
                  key (:mesh-key (first results))]
              (is (apply = results))
              (is (every? #(= 3 (:tiers %)) results))
              (doseq [tier (range 3)]
                (is (pos? (:vertex-count (decode-tier! c key tier)))))
              (is (empty? @(:inflight c)))
              (is (empty? (filter fs/directory? (fs/list-dir (:dir c)))))))
          (finally
            (.countDown release)
            (remove-watch (:inflight c) ::admission)))))))

(deftest concurrent-publication-and-eviction-remain-recoverable
  (with-cache!
    (fn [c root pool]
      (let [sources (mapv #(source! root (str % ".stl") (+ 1.0 %)) (range 4))
            start (CountDownLatch. 1)
            producers (mapv #(.submit pool ^Callable
                                      (fn [] (.await start) (cache/ensure! c %))) sources)
            sweeps (.submit pool ^Callable
                            (fn [] (.await start)
                              (dotimes [_ 50] (cache/evict! (assoc c :cap-bytes 0)))
                              :complete))]
        (.countDown start)
        (is (= :complete (.get sweeps 30 TimeUnit/SECONDS)))
        (doseq [call producers] (is (:mesh-key (.get call 30 TimeUnit/SECONDS))))
        ;; Eviction can remove a completed mesh, but cannot corrupt publication
        ;; or turn a valid source into a permanently failed job.
        (doseq [source sources]
          (let [result (cache/ensure! c source)]
            (is (pos? (:vertex-count (decode-tier! c (:mesh-key result) 0))))))))))

(deftest disappearance-during-eviction-does-not-hide-other-io-errors
  (with-cache!
    (fn [c root _]
      (let [result (cache/ensure! c (source! root "source.stl" 1.0))
            target (cache/tier-file c (:mesh-key result) 0)
            size! fs/size]
        (testing "a real file removed between enumeration and stat is skipped"
          ;; Fault injection at the filesystem boundary; size still performs its
          ;; real read and throws the filesystem's own NoSuchFileException.
          (with-redefs [fs/size (fn [file]
                                  (when (= (fs/path file) (fs/path target)) (fs/delete-if-exists file))
                                  (size! file))]
            (cache/evict! (assoc c :cap-bytes 0)))
          (is (zero? (cache/cache-size! c))))
        (testing "unrelated filesystem failures still propagate"
          (cache/ensure! c (source! root "source.stl" 1.0))
          (with-redefs [fs/size (fn [_] (throw (java.nio.file.AccessDeniedException. "denied")))]
            (is (thrown? java.nio.file.AccessDeniedException (cache/evict! c)))))))))

(deftest failed-publication-cleans-staging-and-can-retry
  (with-cache!
    (fn [c root _]
      (let [source (source! root "source.stl" 1.0)
            key (cache/sha256! source)
            obstruction (cache/tier-file c key 2)]
        (fs/create-dirs obstruction)
        (spit (fs/file obstruction "keep") "occupied")
        (is (thrown? java.io.IOException (cache/ensure! c source)))
        (is (empty? @(:inflight c)))
        (is (not (fs/exists? (cache/tier-file c key 0))) "failure cannot publish the readiness marker")
        (is (= "occupied" (slurp (fs/file obstruction "keep"))))
        (is (= [(fs/path obstruction)] (vec (filter fs/directory? (fs/list-dir (:dir c))))))
        (fs/delete-tree obstruction)
        (is (= key (:mesh-key (cache/ensure! c source))))
        (doseq [tier (range 3)] (is (pos? (:vertex-count (decode-tier! c key tier)))))))))
