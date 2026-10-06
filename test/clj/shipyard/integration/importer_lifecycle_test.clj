(ns shipyard.integration.importer-lifecycle-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.http.jobs :as mesh-jobs]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.archive :as archive]
            [shipyard.importer.db :as importer]
            [shipyard.integration.thumbnail-cache-test :as previews]
            [shipyard.jobs :as jobs]
            [shipyard.settings.db :as settings])
  (:import [java.util.concurrent CountDownLatch ExecutorService TimeUnit]))

(defn- await-release! [^CountDownLatch release interrupted]
  (loop []
    (when-not (try (.await release) true
                   (catch InterruptedException _ (deliver interrupted true) false))
      (recur))))

(deftest shutdown-drains-an-import-that-never-reached-the-workspace
  (let [started (fixture/start!) sys (:system started)
        owner (:shipyard.importer/db sys)
        session (importer/prepare! owner (archives/archive! (:temp started)))
        shared-store (:shipyard.store/db sys)
        ^ExecutorService pool (get-in sys [:shipyard.jobs/pool :pool])
        entered (CountDownLatch. 1) release (CountDownLatch. 1)
        interrupted (promise) written (promise) stopping (atom nil)]
    (try
      (previews/await! #(= {:running 0 :queued 0} (jobs/progress! (get-in session [:thumbnails :scope]))))
      (is (nil? (importer/session! {:workspace (:shipyard.workspace/db sys)})))
      (is (jobs/submit! (get-in session [:jobs :scope])
                        #(do (.countDown entered)
                             (await-release! release interrupted)
                             (settings/save-library-root! (:store session) "/staging-reader/finished")
                             (deliver written true))))
      (is (.await entered 5 TimeUnit/SECONDS))
      (reset! stopping (future (fixture/stop! started) :stopped))
      (is (= true (deref interrupted 5000 ::timeout)))
      (testing "the owner retains staging and shared dependencies while a reader finishes"
        (is (= ::waiting (deref @stopping 100 ::waiting)))
        (is (:closed? @(:state owner)))
        (is (contains? (:sessions @(:state owner)) (:id session)))
        (is (fs/exists? (:directory session)))
        (is (not (d/closed? (get-in session [:store :conn]))))
        (is (not (d/closed? (:conn shared-store))))
        (is (not (.isShutdown pool))))
      (.countDown release)
      (is (= :stopped (deref @stopping 60000 ::timeout)))
      (is (= true (deref written 100 ::timeout)))
      (is (.isTerminated pool))
      (is (d/closed? (:conn shared-store)))
      (is (d/closed? (get-in session [:store :conn])))
      (is (not (fs/exists? (:directory session))))
      (is (empty? (:sessions @(:state owner))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shut down"
                            (importer/prepare! owner "unused.zip")))
      (finally
        (.countDown release)
        (when @stopping (deref @stopping 60000 nil))
        (fixture/stop! started)))))

(deftest shutdown-cannot-miss-a-session-still-being-prepared
  (let [started (fixture/start!) sys (:system started) owner (:shipyard.importer/db sys)
        zip (archives/archive! (:temp started)) extract! archive/extract!
        entered (promise) release (CountDownLatch. 1)
        preparing (atom nil) stopping (atom nil)]
    (try
      ;; Hold real extraction at a deterministic boundary after staging has
      ;; opened. Shutdown must wait for preparation before halting dependencies.
      (with-redefs [archive/extract! (fn [path directory]
                                       (deliver entered directory)
                                       (.await release)
                                       (extract! path directory))]
        (reset! preparing (future (importer/prepare! owner zip)))
        (let [directory (deref entered 5000 ::timeout)]
          (is (not= ::timeout directory))
          (is (= 1 (count (:sessions @(:state owner)))))
          (reset! stopping (future (fixture/stop! started) :stopped))
          (is (= ::waiting (deref @stopping 100 ::waiting)))
          (is (not (d/closed? (get-in sys [:shipyard.store/db :conn]))))
          (.countDown release)
          (let [session (deref @preparing 30000 ::timeout)]
            (is (not= ::timeout session))
            (is (= :stopped (deref @stopping 60000 ::timeout)))
            (is (d/closed? (get-in session [:store :conn])))
            (is (not (fs/exists? directory)))
            (is (empty? (:sessions @(:state owner))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session is closed"
                                  (importer/plan! session))))))
      (finally
        (.countDown release)
        (when @preparing (deref @preparing 30000 nil))
        (when @stopping (deref @stopping 60000 nil))
        (fixture/stop! started)))))

(deftest partial-preparation-failure-closes-already-opened-resources
  (let [started (fixture/start!) owner (get-in started [:system :shipyard.importer/db])
        zip (archives/archive! (:temp started)) acquired (atom nil)]
    (try
      (with-redefs [mesh-jobs/open! (fn [_]
                                      (reset! acquired (first (vals (:sessions @(:state owner)))))
                                      (throw (ex-info "Injected scope acquisition failure" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scope acquisition failure"
                              (importer/prepare! owner zip))))
      (is (some? (:store @acquired)))
      (is (d/closed? (get-in @acquired [:store :conn])))
      (is (not (fs/exists? (:directory @acquired))))
      (is (empty? (:sessions @(:state owner))))
      (let [session (importer/prepare! owner zip)]
        (is (not (d/closed? (get-in session [:store :conn]))))
        (importer/close! session))
      (finally (fixture/stop! started)))))

(deftest cleanup-failure-retains-ownership-for-shutdown-to-retry
  (let [started (fixture/start!) owner (get-in started [:system :shipyard.importer/db])
        session (importer/prepare! owner (archives/archive! (:temp started)))
        delete-tree! fs/delete-tree]
    (try
      (with-redefs [fs/delete-tree (fn [path & args]
                                     (if (= path (:directory session))
                                       (throw (ex-info "Injected staging removal failure" {}))
                                       (apply delete-tree! path args)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"staging removal failure"
                              (importer/close! session))))
      (is (d/closed? (get-in session [:store :conn])))
      (is (fs/exists? (:directory session)))
      (is (contains? (:sessions @(:state owner)) (:id session)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session is closed"
                            (importer/plan! session)))
      (fixture/stop! started)
      (is (empty? (:sessions @(:state owner))))
      (is (not (fs/exists? (:directory session))))
      (is (nil? (importer/close! session)) "Repeated close is harmless")
      (finally (fixture/stop! started)))))

(deftest publication-releases-session-without-workspace-cleanup
  (let [started (fixture/start!) owner (get-in started [:system :shipyard.importer/db])
        session (importer/prepare! owner (archives/archive! (:temp started)))
        plan (importer/plan! session)]
    (try
      (is (= {:files 3 :parts 2} (importer/commit! owner session)))
      (is (every? #(fs/regular-file? (fs/path (:root started) (:path %))) plan))
      (is (d/closed? (get-in session [:store :conn])))
      (is (not (fs/exists? (:directory session))))
      (is (empty? (:sessions @(:state owner))))
      (is (nil? (importer/close! session)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session is closed"
                            (importer/commit! owner session)))
      (finally (fixture/stop! started)))))

(deftest failed-halt-retains-shared-dependencies-and-attempts-other-sessions
  (let [started (fixture/start!) sys (:system started) owner (:shipyard.importer/db sys)
        zip (archives/archive! (:temp started))
        first-session (importer/prepare! owner zip) second-session (importer/prepare! owner zip)
        close-jobs! mesh-jobs/close!]
    (try
      (with-redefs [mesh-jobs/close! (fn [jobs]
                                       (if (identical? jobs (:jobs first-session))
                                         (throw (ex-info "Injected mesh drain failure" {}))
                                         (close-jobs! jobs)))]
        (is (thrown? Exception (fixture/stop! started))))
      (is (:closed? @(:state owner)))
      (is (= #{(:id first-session)} (set (keys (:sessions @(:state owner))))))
      (is (not (d/closed? (get-in first-session [:store :conn]))))
      (is (fs/exists? (:directory first-session)))
      (is (d/closed? (get-in second-session [:store :conn])))
      (is (not (fs/exists? (:directory second-session))))
      (is (not (d/closed? (get-in sys [:shipyard.store/db :conn]))))
      (is (not (.isShutdown ^ExecutorService (get-in sys [:shipyard.jobs/pool :pool]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shut down"
                            (importer/prepare! owner zip)))
      (fixture/stop! started)
      (is (d/closed? (get-in first-session [:store :conn])))
      (is (not (fs/exists? (:directory first-session))))
      (is (empty? (:sessions @(:state owner))))
      (finally (fixture/stop! started)))))

(deftest cleanup-failure-after-publication-keeps-published-files
  (let [started (fixture/start!) owner (get-in started [:system :shipyard.importer/db])
        session (importer/prepare! owner (archives/archive! (:temp started)))
        plan (importer/plan! session) delete-tree! fs/delete-tree]
    (try
      (with-redefs [fs/delete-tree (fn [path & args]
                                     (if (= path (:directory session))
                                       (throw (ex-info "Injected published staging cleanup failure" {}))
                                       (apply delete-tree! path args)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"staging cleanup failure"
                              (importer/commit! owner session))))
      (is (every? #(fs/regular-file? (fs/path (:root started) (:path %))) plan))
      (is (d/closed? (get-in session [:store :conn])))
      (is (contains? (:sessions @(:state owner)) (:id session)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session is closed"
                            (importer/commit! owner session)))
      (fixture/stop! started)
      (is (not (fs/exists? (:directory session))))
      (is (empty? (:sessions @(:state owner))))
      (finally (fixture/stop! started)))))
