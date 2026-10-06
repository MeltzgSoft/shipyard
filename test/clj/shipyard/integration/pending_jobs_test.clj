(ns shipyard.integration.pending-jobs-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.jobs :as jobs]
            [shipyard.catalog.db :as catalog]
            [shipyard.part.orientation :as orientation]
            [shipyard.thumbnail.part :as parts]
            [shipyard.thumbnail.cache :as thumbnails]
            [shipyard.part-browser.thumbnail :as renderer]
            [shipyard.integration.thumbnail-cache-test :as previews])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- worker! [pool priority]
  (jobs/scope! pool {:priority priority}))

(deftest hundreds-of-accepted-descriptors-complete-without-polling
  (let [pool (ig/init-key :shipyard.jobs/pool {:threads 2 :queue-size 400 :interactive-reserve 8})
        scope (worker! pool :bulk) blockers (jobs/scope! pool)
        entered (CountDownLatch. 2) release (CountDownLatch. 1)
        done (CountDownLatch. 300) calls (atom [])
        descriptors (mapv (fn [id] {:key id :run! (fn [id] (swap! calls conj id) (.countDown done)) :args [id]}) (range 300))]
    (try
      (dotimes [_ 2] (jobs/submit! blockers #(do (.countDown entered) (.await release))))
      (is (.await entered 5 TimeUnit/SECONDS))
      (is (= {:accepted? true :submitted 300 :duplicates 0} (jobs/submit-batch! scope descriptors)))
      (is (= {:accepted? true :submitted 0 :duplicates 300} (jobs/submit-batch! scope descriptors)))
      (is (= {:running 0 :queued 300} (jobs/progress! scope)))
      (.countDown release)
      (is (.await done 10 TimeUnit/SECONDS))
      (previews/await! #(zero? (:running (jobs/progress! scope))))
      (is (= (vec (range 300)) (vec (sort @calls))))
      (is (= {:accepted 300 :pending 0 :running 0 :completed 300 :failed 0 :cancelled 0 :rejected 0} (jobs/counts! scope)))
      (finally (.countDown release) (ig/halt-key! :shipyard.jobs/pool pool)))))

(deftest fair-priorities-share-one-worker-and-reserve-admission
  (let [pool (ig/init-key :shipyard.jobs/pool {:threads 1 :queue-size 20 :interactive-reserve 4})
        bulk (worker! pool :bulk) interactive (worker! pool :interactive)
        entered (CountDownLatch. 1) release (CountDownLatch. 1) order (atom []) done (CountDownLatch. 16)
        descriptor (fn [label] {:key label :run! #(do (swap! order conj label) (.countDown done))})]
    (try
      (jobs/submit! bulk #(do (.countDown entered) (.await release)))
      (is (.await entered 5 TimeUnit/SECONDS))
      (is (:accepted? (jobs/submit-batch! bulk (mapv #(descriptor [:bulk %]) (range 10)))))
      (is (:accepted? (jobs/submit-batch! interactive (mapv #(descriptor [:interactive %]) (range 6)))))
      (.countDown release)
      (is (.await done 5 TimeUnit/SECONDS))
      (is (= [[:interactive 0] [:interactive 1] [:interactive 2] [:bulk 0]
              [:interactive 3] [:interactive 4] [:interactive 5] [:bulk 1]] (subvec @order 0 8)))
      (is (= 1 (.getLargestPoolSize ^java.util.concurrent.ThreadPoolExecutor (:pool pool))))
      (finally (.countDown release) (ig/halt-key! :shipyard.jobs/pool pool)))))

(deftest overload-rejects-the-whole-batch-and-remains-observable
  (let [pool (ig/init-key :shipyard.jobs/pool {:threads 1 :queue-size 8 :interactive-reserve 2})
        bulk (worker! pool :bulk) interactive (worker! pool :interactive)
        entered (CountDownLatch. 1) release (CountDownLatch. 1) called (atom [])
        task (fn [id] {:key id :run! #(swap! called conj id)})]
    (try
      (jobs/submit! interactive #(do (.countDown entered) (.await release)))
      (is (.await entered 5 TimeUnit/SECONDS))
      (is (= {:accepted? false :reason :full :requested 7} (jobs/submit-batch! bulk (mapv task (range 7)))))
      (is (= {:running 0 :queued 0} (jobs/progress! bulk)))
      (is (:accepted? (jobs/submit-batch! bulk (mapv task (range 6)))))
      (is (:accepted? (jobs/submit-batch! interactive (mapv task [6 7]))))
      (is (= :full (:reason (jobs/submit-batch! interactive [(task 8)]))))
      (jobs/close! bulk)
      (is (= 6 (:cancelled (jobs/counts! bulk))))
      (is (= 7 (:rejected (jobs/counts! bulk))))
      (.countDown release)
      (previews/await! #(= #{6 7} (set @called)))
      (finally (.countDown release) (ig/halt-key! :shipyard.jobs/pool pool)))))

(deftest failures-release-descriptors-and-shutdown-does-not-resume-pending-work
  (let [pool (ig/init-key :shipyard.jobs/pool {:threads 1 :queue-size 8}) scope (jobs/scope! pool)
        entered (CountDownLatch. 1) never (CountDownLatch. 1) ran (atom false)]
    (try
      (is (jobs/submit! scope #(throw (ex-info "Visible failure" {}))))
      (previews/await! #(= 1 (:failed (jobs/counts! scope))))
      (jobs/submit! scope #(do (.countDown entered) (.await never)))
      (is (.await entered 5 TimeUnit/SECONDS))
      (jobs/submit! scope #(reset! ran true))
      (ig/halt-key! :shipyard.jobs/pool pool)
      (is (false? @ran))
      (is (= 2 (:cancelled (jobs/counts! scope))))
      (is (= :closed (:reason (jobs/submit-batch! scope [{:key :late :run! (fn [])}]))))
      (let [restarted (ig/init-key :shipyard.jobs/pool {:threads 1 :queue-size 8}) fresh (jobs/scope! restarted)]
        (try
          (is (= {:running 0 :queued 0} (jobs/progress! fresh)))
          (is (zero? (:accepted (jobs/counts! fresh))))
          (finally (ig/halt-key! :shipyard.jobs/pool restarted))))
      (finally (.countDown never) (ig/halt-key! :shipyard.jobs/pool pool)))))

(deftest import-preview-cancellation-drains-staging-readers-before-store-closure
  (let [started (fixture/start!) sys (:system started)
        deps {:library (:shipyard.library/index sys) :cache (:shipyard.mesh/cache sys)
              :jobs (:shipyard.http/jobs sys) :thumbnails (:shipyard.thumbnail/cache sys)}
        session (importer/prepare! (:shipyard.importer/db sys) (archives/archive! (:temp started)))
        previews (:thumbnails session) entered (CountDownLatch. 1) release (CountDownLatch. 1)
        interrupted (promise) read-open (promise) closing (atom nil)]
    (try
      (previews/await! #(zero? (+ (:running (jobs/progress! (:scope previews))) (:queued (jobs/progress! (:scope previews))))))
      (thumbnails/request! previews {:test :staging-reader}
                           #(do (.countDown entered)
                                (loop [] (when-not (try (.await release) true (catch InterruptedException _ (deliver interrupted true) false)) (recur)))
                                (deliver read-open (not (d/closed? (get-in session [:store :conn]))))
                                (renderer/png! previews/triangle nil)))
      (is (.await entered 5 TimeUnit/SECONDS))
      (reset! closing (future (importer/close! session)))
      (is (= true (deref interrupted 5000 ::timeout)))
      (is (= ::waiting (deref @closing 100 ::waiting)))
      (is (not (d/closed? (get-in session [:store :conn]))))
      (.countDown release)
      (is (not= ::timeout (deref @closing 5000 ::timeout)))
      (is (= true @read-open))
      (is (d/closed? (get-in session [:store :conn])))
      (is (= 1 (:cancelled (jobs/counts! (:scope previews)))))
      (is (empty? @(:children (:thumbnails deps))))
      (let [done (promise)]
        (is (jobs/submit! (:scope (:thumbnails deps)) #(deliver done true)))
        (is (= true (deref done 5000 ::timeout))))
      (finally
        (.countDown release)
        (if @closing (deref @closing 35000 nil) (importer/close! session))
        (fixture/stop! started)))))

(deftest changed-appearance-cannot-publish-an-old-accepted-import-preview
  (let [started (fixture/start! false fixture/library! fixture/author! {:shipyard.jobs/pool {:threads 1 :queue-size 16}})
        sys (:system started) shared (:shipyard.jobs/pool sys) blocker (jobs/scope! shared)
        entered (CountDownLatch. 1) release (CountDownLatch. 1)
        deps {:library (:shipyard.library/index sys) :cache (:shipyard.mesh/cache sys)
              :jobs (:shipyard.http/jobs sys) :thumbnails (:shipyard.thumbnail/cache sys)} session (atom nil)]
    (try
      (jobs/submit! blocker #(do (.countDown entered) (.await release)))
      (is (.await entered 5 TimeUnit/SECONDS))
      (reset! session (importer/prepare! (:shipyard.importer/db sys) (archives/archive! (:temp started))))
      (let [session @session id (first (keys (:parts (catalog/listing! (:catalog session)))))
            preview-deps (assoc session :cache (:cache deps) :import-session true)
            scope (get-in session [:thumbnails :scope])]
        (catalog/save-part-orientation! (:catalog session) id (orientation/from-euler-degrees 45 0 0))
        (.countDown release)
        (previews/await! #(zero? (+ (:queued (jobs/progress! scope)) (:running (jobs/progress! scope)))))
        (is (= 1 (:failed (jobs/counts! scope))))
        (is (= :preparing (:state (parts/request! preview-deps id 1 false))))
        (previews/await! #(zero? (+ (:queued (jobs/progress! scope)) (:running (jobs/progress! scope)))))
        (is (= :ready (:state (parts/request! preview-deps id 1 false))))
        (is (= 2 (:completed (jobs/counts! scope)))))
      (finally (.countDown release) (when @session (importer/close! @session)) (fixture/stop! started)))))

(deftest failed-publication-restores-files-and-reopens-import-preview-ownership
  (let [started (fixture/start! false fixture/library! fixture/author! {:shipyard.jobs/pool {:threads 1}})
        sys (:system started) pool (:shipyard.jobs/pool sys) blocker (jobs/scope! pool)
        entered (CountDownLatch. 1) release (CountDownLatch. 1)
        deps {:library (:shipyard.library/index sys) :catalog (:shipyard.catalog/db sys)
              :cache (:shipyard.mesh/cache sys) :jobs (:shipyard.http/jobs sys)
              :thumbnails (:shipyard.thumbnail/cache sys)} session (atom nil)
        before (catalog/listing! (:catalog deps))]
    (try
      (jobs/submit! blocker #(do (.countDown entered) (.await release)))
      (is (.await entered 5 TimeUnit/SECONDS))
      (reset! session (importer/prepare! (:shipyard.importer/db sys) (archives/archive! (:temp started))))
      (let [session @session plan (importer/plan! session) missing (:file (second plan))
            mtime (fs/file-time->millis (fs/last-modified-time missing))
            bytes (java.nio.file.Files/readAllBytes (fs/path missing))
            scope (get-in session [:thumbnails :scope])]
        (fs/delete missing)
        (is (thrown? Exception (importer/commit! (:shipyard.importer/db sys) session)))
        (is (fs/regular-file? (:file (first plan))) "Already moved files are restored")
        (is (false? (:closed? @(:state scope))))
        (is (= 2 (:cancelled (jobs/counts! scope))))
        (is (= before (catalog/listing! (:catalog deps))))
        (is (not (d/closed? (get-in session [:store :conn]))))
        (with-open [out (io/output-stream missing)] (.write out ^bytes bytes))
        (fs/set-last-modified-time missing mtime)
        (.countDown release)
        (previews/await! #(zero? (+ (:queued (jobs/progress! scope)) (:running (jobs/progress! scope)))))
        (is (= :preparing (:state (parts/request! (assoc session :cache (:cache deps) :import-session true)
                                                  (:group (second plan)) 1 false))))
        (previews/await! #(zero? (+ (:queued (jobs/progress! scope)) (:running (jobs/progress! scope)))))
        (is (= 2 (:completed (jobs/counts! scope))))
        (is (= before (catalog/listing! (:catalog deps)))))
      (finally (.countDown release) (when @session (importer/close! @session)) (fixture/stop! started)))))
