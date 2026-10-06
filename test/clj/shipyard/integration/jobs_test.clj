(ns shipyard.integration.jobs-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.jobs :as workers]
            [shipyard.http.jobs :as mesh-jobs]
            [shipyard.http.urls :as urls]
            [shipyard.library.index :as index]
            [shipyard.thumbnail.cache :as thumbnails]
            [shipyard.integration.thumbnail-cache-test :as previews])
  (:import [java.util.concurrent CountDownLatch ThreadPoolExecutor TimeUnit]))

(deftest all-job-types-share-capacity-and-retry-after-saturation
  (let [started (fixture/start! false fixture/library! fixture/author! {:shipyard.jobs/pool {:threads 2 :queue-size 8 :interactive-reserve 2}}) sys (:system started) shared (:shipyard.jobs/pool sys)
        ^ThreadPoolExecutor pool (:pool shared) blockers (workers/scope! shared)
        entered (CountDownLatch. 2) release (CountDownLatch. 1)
        library (:shipyard.library/index sys) jobs (:shipyard.http/jobs sys)
        cache (:shipyard.thumbnail/cache sys) id (:prow fixture/ids)
        recovered (promise)]
    (try
      (dotimes [_ 2]
        (is (workers/submit! blockers #(do (.countDown entered) (.await release)))))
      (is (.await entered 5 TimeUnit/SECONDS))
      (dotimes [_ (:queue-size shared)] (is (workers/submit! blockers (fn []))))
      (is (false? (workers/submit! blockers #(throw (ex-info "Must not run inline" {})))))
      (is (= 2 (.getLargestPoolSize pool)))
      (is (= :running (:state (mesh-jobs/submit! jobs id (index/fresh-source-file! library id)))))
      (is (nil? (mesh-jobs/status jobs id)) "Rejected work leaves no permanent running claim")
      (is (= :running (:state (mesh-jobs/submit-facet-backfill! jobs :test #(deliver recovered true)))))
      (is (= :overloaded (:state (thumbnails/request! cache :test (constantly (byte-array 0))))))
      (is (empty? @(:jobs cache)))
      (.countDown release)
      (workers/close! blockers)
      (previews/await! #(= :ready (:state (mesh-jobs/submit! jobs id (index/fresh-source-file! library id)))))
      (mesh-jobs/submit-facet-backfill! jobs :test #(deliver recovered true))
      (is (= true (deref recovered 5000 ::timeout)))
      (let [url (previews/image-url! (:handler started) (str "/thumbnails/" (urls/encode-id id)))]
        (is (re-find #"/thumbnail-images/" url)))
      (finally (.countDown release) (fixture/stop! started)))))

(deftest closing-import-drains-only-its-jobs-before-closing-its-store
  (let [started (fixture/start!) sys (:system started) shared (:shipyard.jobs/pool sys)
        root-jobs (:shipyard.http/jobs sys) thumbnails (:shipyard.thumbnail/cache sys)
        session (importer/prepare! (:shipyard.importer/db sys)
                                   (archives/archive! (:temp started)))
        release-root (CountDownLatch. 1) release-import (CountDownLatch. 1)
        entered (CountDownLatch. 2) interrupted (promise) queued-ran (atom false)
        closing (atom nil)]
    (try
      (previews/await! #(= {:running 0 :queued 0} (workers/progress! (get-in session [:thumbnails :scope]))))
      (doseq [scope [(:scope root-jobs) (:scope thumbnails) (get-in session [:jobs :scope])]]
        (is (identical? (:pool shared) (:pool scope))))
      (workers/submit! (:scope root-jobs) #(do (.countDown entered) (.await release-root)))
      (workers/submit! (get-in session [:jobs :scope])
                       #(do (.countDown entered)
                            (loop []
                              (when-not (try (.await release-import) true
                                             (catch InterruptedException _ (deliver interrupted true) false))
                                (recur)))))
      (is (.await entered 5 TimeUnit/SECONDS))
      (workers/submit! (get-in session [:jobs :scope]) #(reset! queued-ran true))
      (reset! closing (future (importer/close! session)))
      (is (= true (deref interrupted 5000 ::timeout)))
      (is (= ::waiting (deref @closing 100 ::waiting)))
      (is (not (d/closed? (get-in session [:store :conn]))))
      (.countDown release-import)
      (is (not= ::timeout (deref @closing 5000 ::timeout)))
      (is (d/closed? (get-in session [:store :conn])))
      (is (false? @queued-ran))
      (is (not (.isShutdown ^ThreadPoolExecutor (:pool shared))))
      (let [rendered (promise)]
        (is (workers/submit! (:scope thumbnails) #(deliver rendered true)))
        (is (= true (deref rendered 5000 ::timeout)) "Other work continues while library work is still held"))
      (finally
        (.countDown release-root)
        (.countDown release-import)
        (if @closing (deref @closing 35000 nil) (importer/close! session))
        (fixture/stop! started)))))
