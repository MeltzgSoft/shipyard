(ns shipyard.integration.lifecycle-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.http.jobs]
            [shipyard.jobs]
            [shipyard.library.index]
            [shipyard.settings.db :as settings]
            [shipyard.store.db :as store])
  (:import [java.util.concurrent CountDownLatch ExecutorService Future TimeUnit]))

(def ^:private halt-completion-timeout-ms
  ;; A hang guard for the whole shutdown, including database close/flush after
  ;; the production worker termination budget (30 seconds) has elapsed.
  60000)

(defn- await-release! [^CountDownLatch release interrupted]
  (loop []
    (when-not (try
                (.await release)
                true
                (catch InterruptedException _
                  (deliver interrupted true)
                  false))
      (recur))))

(defn- assert-orderly-shutdown! [phase]
  (let [directory (fs/create-temp-dir {:prefix "shipyard-lifecycle-"})
        system (ig/init {:shipyard.store/db {:directory (str directory)}
                         :shipyard.jobs/pool {:store (ig/ref :shipyard.store/db)}
                         :shipyard.library/index {:store (ig/ref :shipyard.store/db)}
                         :shipyard.http/jobs {:workers (ig/ref :shipyard.jobs/pool) :library (ig/ref :shipyard.library/index) :cache {}}})
        database (:shipyard.store/db system)
        ^ExecutorService pool (get-in system [:shipyard.jobs/pool :pool])
        entered (promise)
        interrupted (promise)
        release (CountDownLatch. 1)
        finish! (fn [database]
                  (deliver entered true)
                  (await-release! release interrupted)
                  (settings/save-library-root! database "/worker/finished"))
        ^Future worker (.submit pool ^Runnable
                                #(if (= phase :before-write)
                                   (finish! database)
                                   (store/write! database
                                                 (fn [conn]
                                                   (finish! (assoc database :conn conn))))))
        stopping (atom nil)]
    (try
      (is (= true (deref entered 5000 ::timeout)))
      (reset! stopping (future (ig/halt! system)))
      (is (= true (deref interrupted 5000 ::timeout))
          "shutdown has interrupted the real executor while its work is held")
      (is (= ::waiting (deref @stopping 100 ::waiting))
          "Integrant must not complete shutdown while a worker can still write")
      (is (not (d/closed? (:conn database))))
      (.countDown release)
      ;; Shutdown owns the completion boundary. A separate five-second get on
      ;; the worker imposed an unrelated deadline on real transaction/flush I/O.
      (let [halted (deref @stopping halt-completion-timeout-ms ::timeout)]
        (is (nil? halted)
            (str "shutdown did not complete: phase=" phase
                 ", worker-done=" (.isDone worker)
                 ", executor-terminated=" (.isTerminated pool)
                 ", store-closed=" (d/closed? (:conn database))))
        (when-not (= ::timeout halted)
          (is (.isDone worker) "the worker must already be complete when shutdown returns")
          (.get worker 0 TimeUnit/SECONDS)
          (is (.isTerminated pool))
          (is (d/closed? (:conn database)))
          (let [reopened (store/open! directory)]
            (try
              (is (= "/worker/finished" (settings/library-root! reopened)))
              (finally (store/close! reopened))))))
      (finally
        (.countDown release)
        ;; Never issue a second shutdown or remove the database while the first
        ;; halt is still using it. A failed completed halt can be retried now
        ;; that the deliberately blocked worker has been released.
        (when (or (nil? @stopping)
                  (try
                    (not= ::timeout (deref @stopping halt-completion-timeout-ms ::timeout))
                    (catch Exception _ true)))
          (ig/halt! system)
          (fs/delete-tree directory))))))

(deftest shutdown-finishes-workers-before-closing-the-store
  (doseq [phase [:before-write :in-transaction]]
    (testing (name phase)
      (assert-orderly-shutdown! phase))))
