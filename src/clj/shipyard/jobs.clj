(ns shipyard.jobs
  "One application executor with independently drainable owners of background work."
  (:require [integrant.core :as ig]
            [shipyard.jobs.transforms :as transforms])
  (:import [java.util.concurrent ArrayBlockingQueue RejectedExecutionException
            ThreadFactory ThreadPoolExecutor TimeUnit]))

(defn scope!
  "Give a subsystem its own cancellation boundary on the shared executor."
  [{:keys [pool scopes]}]
  (let [state (atom {:closed? false :tasks {}})]
    (swap! scopes conj state)
    {:pool pool :scopes scopes :state state}))

(defn- finished! [state token]
  (locking state
    (swap! state update :tasks dissoc token)
    (.notifyAll ^Object state)))

(defn submit!
  "Admit work without blocking or executing on the caller. False means full/closed.
  A task remains tracked until its actual body exits, even if it ignores interruption."
  [{:keys [^ThreadPoolExecutor pool state]} task]
  (locking state
    (if (:closed? @state) false
        (let [token (Object.)
              runnable (fn []
                         (try
                           (when (locking state
                                   (when (and (not (:closed? @state)) (get-in @state [:tasks token]))
                                     (swap! state assoc-in [:tasks token :thread] (Thread/currentThread))
                                     true))
                             (task))
                           (finally (finished! state token))))]
          (swap! state assoc-in [:tasks token] {:runnable runnable})
          (try
            (.execute pool ^Runnable runnable)
            true
            (catch RejectedExecutionException _
              (finished! state token)
              false))))))

(defn close!
  "Cancel queued work and interrupt/drain only this scope before its resources close."
  [{:keys [^ThreadPoolExecutor pool scopes state]}]
  (locking state
    (swap! state assoc :closed? true)
    (doseq [[token {:keys [thread runnable]}] (:tasks @state)]
      (if thread
        (.interrupt ^Thread thread)
        (do (.remove pool ^Runnable runnable)
            (swap! state update :tasks dissoc token))))
    (let [deadline (+ (System/nanoTime) 30000000000)]
      (loop []
        (when (seq (:tasks @state))
          (let [remaining (- deadline (System/nanoTime))]
            (when-not (pos? remaining)
              (throw (ex-info "Background jobs did not stop; their resources remain open" {})))
            (.wait ^Object state (long (max 1 (quot remaining 1000000))))
            (recur)))))
    (swap! scopes disj state))
  nil)

(defmethod ig/init-key :shipyard.jobs/pool [_ options]
  (let [{:keys [threads queue-size] :as limits} (transforms/limits options)
        counter (atom 0)
        factory (reify ThreadFactory
                  (newThread [_ task]
                    (doto (Thread. ^Runnable task (str "shipyard-job-" (swap! counter inc)))
                      (.setDaemon true))))]
    (assoc limits :scopes (atom #{})
           :pool (ThreadPoolExecutor. (int threads) (int threads) 0 TimeUnit/MILLISECONDS
                                      (ArrayBlockingQueue. (int queue-size)) ^ThreadFactory factory))))

(defmethod ig/halt-key! :shipyard.jobs/pool [_ {:keys [^ThreadPoolExecutor pool scopes]}]
  (.shutdownNow pool)
  (doseq [state @scopes] (close! {:pool pool :scopes scopes :state state}))
  (when-not (.awaitTermination pool 30 TimeUnit/SECONDS)
    (throw (ex-info "Background workers did not stop; application resources remain open" {}))))
