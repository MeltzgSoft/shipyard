(ns shipyard.integration.lifecycle-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.http.jobs]
            [shipyard.library.index]
            [shipyard.settings.db :as settings]
            [shipyard.store.db :as store])
  (:import [java.util.concurrent CountDownLatch ExecutorService Future TimeUnit]))

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
                         :shipyard.library/index {:store (ig/ref :shipyard.store/db)}
                         :shipyard.http/jobs {:library (ig/ref :shipyard.library/index) :cache {}}})
        database (:shipyard.store/db system)
        ^ExecutorService pool (get-in system [:shipyard.http/jobs :pool])
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
      (.get worker 5 TimeUnit/SECONDS)
      (is (nil? (deref @stopping 5000 ::timeout)))
      (is (.isTerminated pool))
      (is (d/closed? (:conn database)))
      (let [reopened (store/open! directory)]
        (try
          (is (= "/worker/finished" (settings/library-root! reopened)))
          (finally (store/close! reopened))))
      (finally
        (.countDown release)
        (when-let [halt @stopping] (deref halt 5000 nil))
        (ig/halt! system)
        (fs/delete-tree directory)))))

(deftest shutdown-finishes-workers-before-closing-the-store
  (doseq [phase [:before-write :in-transaction]]
    (testing (name phase)
      (assert-orderly-shutdown! phase))))
