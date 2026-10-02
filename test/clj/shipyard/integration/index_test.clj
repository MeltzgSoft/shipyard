(ns shipyard.integration.index-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.library.index :as index]
            [shipyard.fixtures :as fixtures]
            [shipyard.http.jobs :as jobs]
            [shipyard.jobs :as workers]
            [shipyard.mesh.cache :as cache]
            [shipyard.store.db :as store]
            [shipyard.store.scan-index :as scan-index])
  (:import [java.util.concurrent Callable CountDownLatch TimeUnit]))

(defn- temp-dir ^java.io.File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- source! [root part-id]
  (let [f (io/file root part-id "unsupported.stl")]
    (io/make-parents f)
    (spit f "mesh")
    f))

(deftest refresh-carries-fresh-escort-analysis
  (let [root (temp-dir "shipyard-index")
        part-id "Human Navy Fleet Bundle/Escort/Cyanide Prow Python"
        f (source! root part-id)
        parts [{:part/id part-id :part/source :unsupported}]
        entry (get (index/refresh! parts root {}) part-id)
        stored {part-id (assoc entry
                               :escort-analysis {:volume 42.0})}]
    (testing "fresh source files keep cached escort geometry analysis"
      (is (= {:volume 42.0}
             (get-in (index/refresh! parts root stored)
                     [part-id :escort-analysis]))))
    (testing "changed source files drop cached escort geometry analysis"
      (Thread/sleep 2)
      (spit f "changed")
      (is (nil? (get-in (index/refresh! parts root stored)
                        [part-id :escort-analysis]))))
    (testing "the test did not leave the temp root missing before assertions"
      (is (fs/directory? root)))))

(defn- with-database! [f]
  (let [dir (fs/create-temp-dir {:prefix "shipyard-index-database-"})
        database (store/open! dir)]
    (try (f database dir)
         (finally (store/close! database) (fs/delete-tree dir)))))

(deftest refreshed-index-retracts-stale-fields-and-removed-parts
  (with-database!
    (fn [database dir]
      (let [root (fs/file dir "library")
            id "Bundle/Cruiser/Hull"
            other "Bundle/Cruiser/Prow"
            source (source! root id)
            _ (source! root other)
            library (ig/init-key :shipyard.library/index {:store database :root (str root)})]
        (index/record-mesh-key! library id "old-hash" 12)
        (index/record-escort-analysis! library id {:volume 42.0})
        (testing "unchanged sources reuse the per-part cached metadata"
          (index/set-root! library (str root))
          (is (= "old-hash" (index/mesh-key! library id)))
          (is (= {:volume 42.0} (index/escort-analysis! library id))))
        (spit source "changed-mesh")
        (fs/delete-tree (fs/path root other))
        (index/set-root! library (str root))
        (testing "a new source stamp clears every stale derived field durably"
          (is (= #{id} (set (keys (scan-index/entries! database root)))))
          (is (= #{:mtime :size} (set (keys (get (scan-index/entries! database root) id)))))
          (is (nil? (index/mesh-key! library id)))
          (is (nil? (index/escort-analysis! library id))))
        (testing "legacy EDN index files are unnecessary"
          (is (empty? (fs/glob dir "**/index-*.edn"))))))))

(deftest index-write-failures-roll-back-with-the-enclosing-transaction
  (with-database!
    (fn [database _]
      (let [before {"hull" {:mtime 1 :size 2 :mesh-key "original"}}]
        (scan-index/replace! database "/library" before)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"abort activation"
             (store/write! database
                           (fn [conn]
                             (let [transactional (assoc database :conn conn)]
                               (scan-index/replace! transactional "/library" {"prow" {:mtime 3 :size 4}})
                               (scan-index/put-entry! transactional "/library" "fin" {:mtime 5 :size 6})
                               (throw (ex-info "abort activation" {})))))))
        (is (= before (scan-index/entries! database "/library")))))))

(deftest late-preprocessing-results-cannot-cross-roots-or-source-stamps
  (with-database!
    (fn [database dir]
      (let [a (fs/file dir "a") b (fs/file dir "b")
            id "Bundle/Cruiser/Hull"
            source-a (source! a id)
            source-b (source! b id)
            library (ig/init-key :shipyard.library/index {:store database :root (str a)})
            submitted-a (assoc (index/part-state! library id) :source source-a)]
        (index/set-root! library (str b))
        (index/record-mesh-key! library id "wrong-library" 10 submitted-a)
        (testing "the same part path in the active root cannot accept an old root's result"
          (is (nil? (index/mesh-key! library id)))
          (is (nil? (get-in (scan-index/entries! database b) [id :mesh-key]))))
        (let [submitted-b (assoc (index/part-state! library id) :source source-b)]
          (spit source-b "changed-under-running-job")
          (index/record-mesh-key! library id "wrong-stamp" 20 submitted-b)
          (is (nil? (index/mesh-key! library id)))
          (index/set-root! library (str b))
          (index/record-mesh-key! library id "still-wrong-stamp" 20 submitted-b)
          (is (nil? (index/mesh-key! library id))))
        (let [current (assoc (index/part-state! library id) :source source-b)]
          (index/record-mesh-key! library id "current-hash" 30 current)
          (is (= "current-hash" (index/mesh-key! library id)))
          (is (= 30 (get-in (scan-index/entries! database b) [id :tris]))))
        (index/set-root! library (str a))
        (is (nil? (index/mesh-key! library id)))
        (index/set-root! library (str b))
        (is (= "current-hash" (index/mesh-key! library id)))))))

(deftest cleared-jobs-cannot-overwrite-a-new-librarys-job-status
  (with-database!
    (fn [database dir]
      (let [a (fs/file dir "a") b (fs/file dir "b")
            id "Bundle/Cruiser/Hull"
            source-a (source! a id) source-b (source! b id)
            _ (doseq [[file size] [[source-a 1.0] [source-b 2.0]]]
                (with-open [out (io/output-stream file)]
                  (.write out ^bytes (fixtures/->binary-stl (fixtures/cube size)))))
            library (ig/init-key :shipyard.library/index {:store database :root (str a)})
            mesh-cache (ig/init-key :shipyard.mesh/cache {:cache-home (str (fs/path dir "cache"))
                                                          :cap-bytes 64000000 :crease-deg 35
                                                          :lod-tiers [1.0 0.25 0.05]})
            shared (ig/init-key :shipyard.jobs/pool {:threads 1})
            pool (:pool shared)
            pending {:state (atom {}) :facet-state (atom {}) :library library :cache mesh-cache :scope (workers/scope! shared)}
            release-old (CountDownLatch. 1) old-finished (CountDownLatch. 1)
            release-new (CountDownLatch. 1)]
        (try
          (.submit pool ^Runnable #(.await release-old))
          (jobs/submit! pending id source-a)
          ;; This barrier runs after the old job completes but before the new
          ;; one begins, exposing any incorrect ready status from the old root.
          (.submit pool ^Runnable #(do (.countDown old-finished) (.await release-new)))
          (jobs/clear! pending)
          (index/set-root! library (str b))
          (jobs/submit! pending id source-b)
          (.countDown release-old)
          (is (.await old-finished 30 TimeUnit/SECONDS))
          (is (= :running (:state (jobs/status pending id))))
          (is (nil? (index/mesh-key! library id)))
          (.countDown release-new)
          (.shutdown pool)
          (is (.awaitTermination pool 30 TimeUnit/SECONDS))
          (is (= {:state :ready :mesh-key (cache/sha256! source-b)} (jobs/status pending id)))
          (is (= (cache/sha256! source-b) (index/mesh-key! library id)))
          (is (nil? (get-in (scan-index/entries! database a) [id :mesh-key])))
          (finally
            (.countDown release-old)
            (.countDown release-new)
            (ig/halt-key! :shipyard.jobs/pool shared)))))))

(deftest delayed-old-source-submission-cannot-claim-the-new-library
  (with-database!
    (fn [database dir]
      (let [a (fs/file dir "a") b (fs/file dir "b")
            id "Bundle/Cruiser/Hull"
            source-a (source! a id) _ (source! b id)
            library (ig/init-key :shipyard.library/index {:store database :root (str a)})
            library-lock (:state library)
            shared (ig/init-key :shipyard.jobs/pool {:threads 1})
            pool (:pool shared)
            pending {:state (atom {}) :facet-state (atom {}) :library library :scope (workers/scope! shared)}
            release (CountDownLatch. 1)]
        (try
          ;; The caller already selected A's source but has not entered submit!.
          (let [caller (.submit pool ^Callable #(do (.await release)
                                                    (jobs/submit! pending id source-a)))]
            (locking library-lock
              (index/set-root! library (str b))
              (jobs/clear! pending))
            (.countDown release)
            (is (nil? (.get caller 30 TimeUnit/SECONDS)))
            (.shutdown pool)
            (is (.awaitTermination pool 30 TimeUnit/SECONDS))
            (is (nil? (jobs/status pending id)))
            (is (nil? (index/mesh-key! library id)))
            (is (nil? (get-in (scan-index/entries! database b) [id :mesh-key]))))
          (finally
            (.countDown release)
            (ig/halt-key! :shipyard.jobs/pool shared)))))))

(deftest a-changed-source-cannot-publish-a-ready-job
  (with-database!
    (fn [database dir]
      (let [root (fs/file dir "library") id "Bundle/Cruiser/Hull"
            source (source! root id)
            _ (with-open [out (io/output-stream source)]
                (.write out ^bytes (fixtures/->binary-stl (fixtures/cube 1.0))))
            library (ig/init-key :shipyard.library/index {:store database :root (str root)})
            mesh-cache (ig/init-key :shipyard.mesh/cache {:cache-home (str (fs/path dir "cache"))
                                                          :cap-bytes 64000000 :crease-deg 35
                                                          :lod-tiers [1.0 0.25 0.05]})
            shared (ig/init-key :shipyard.jobs/pool {:threads 1})
            pool (:pool shared)
            pending {:state (atom {}) :facet-state (atom {}) :library library :cache mesh-cache :scope (workers/scope! shared)}
            release (CountDownLatch. 1)]
        (try
          (.submit pool ^Runnable #(.await release))
          (is (= :running (:state (jobs/submit! pending id source))))
          (with-open [out (io/output-stream source)]
            (.write out ^bytes (fixtures/->binary-stl (fixtures/uv-sphere 1.0 6 12))))
          (.countDown release)
          (.shutdown pool)
          (is (.awaitTermination pool 30 TimeUnit/SECONDS))
          (is (nil? (jobs/status pending id)))
          (is (nil? (index/mesh-key! library id)))
          (is (nil? (get-in (scan-index/entries! database root) [id :mesh-key])))
          (finally
            (.countDown release)
            (ig/halt-key! :shipyard.jobs/pool shared)))))))
