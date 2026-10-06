(ns shipyard.jobs
  "Bounded pending descriptors and fair dispatch on one application-owned pool."
  (:require [integrant.core :as ig]
            [shipyard.jobs.transforms :as transforms])
  (:import [java.util.concurrent LinkedBlockingQueue ThreadFactory ThreadPoolExecutor TimeUnit]))

(def empty-queue clojure.lang.PersistentQueue/EMPTY)

(defn scope!
  "Own cancellation/progress independently; both priorities share execution capacity."
  ([workers] (scope! workers {}))
  ([{:keys [scheduler scopes] :as workers} {:keys [priority] :or {priority :interactive}}]
   (when-not (#{:interactive :bulk} priority)
     (throw (ex-info "Unknown job priority" {:priority priority})))
   (locking scheduler
     (when (:closed? @scheduler) (throw (ex-info "Background scheduler is closed" {})))
     (let [state (atom {:closed? false :tasks {} :counts {:accepted 0 :completed 0 :failed 0 :cancelled 0 :rejected 0}})]
       (swap! scopes conj state)
       (assoc workers :state state :priority priority)))))

(defn progress! [{:keys [scheduler state]}]
  (locking scheduler
    (let [tasks (vals (:tasks @state)) running (count (filter :thread tasks))]
      {:running running :queued (- (count tasks) running)})))

(defn counts! [{:keys [scheduler state] :as scope}]
  (locking scheduler
    (let [{:keys [queued running]} (progress! scope)]
      (assoc (:counts @state) :pending queued :running running))))

(defn submit-batch!
  "Atomically admit lightweight descriptors, deduplicating pending/running keys.
  Each descriptor has :key, :run! and optional :args; preparation stays on workers.
  A rejected batch schedules nothing. No work runs on the caller."
  [{:keys [scheduler state priority] :as scope} descriptors]
  (locking scheduler
    (let [unique (transforms/unique-descriptors descriptors)
          added (filterv #(not (contains? (:tasks @state) (:key %))) unique)
          n (count added) {:keys [queues pending]} @scheduler
          reason (cond
                   (or (:closed? @scheduler) (:closed? @state)) :closed
                   (not (transforms/admits? scope priority pending (count (:bulk queues)) n)) :full)]
      (if reason
        (do (swap! state update-in [:counts :rejected] + n)
            {:accepted? false :reason reason :requested n})
        (do
          (swap! state #(-> % (update :tasks into (map (juxt :key identity) added))
                            (update-in [:counts :accepted] + n)))
          (swap! scheduler #(-> % (update :pending + n)
                                (update-in [:queues priority] into (map (fn [descriptor] [state (:key descriptor)]) added))))
          (.notifyAll ^Object scheduler)
          {:accepted? true :submitted n :duplicates (- (count descriptors) n)})))))

(defn submit!
  "Compatibility for small independent tasks. False is explicit overload/closure."
  [scope task]
  (:accepted? (submit-batch! scope [{:key (Object.) :run! task}])))

(defn- take-next! [scheduler]
  (locking scheduler
    (loop []
      (let [{:keys [closed? queues streak]} @scheduler
            priority (transforms/next-priority (boolean (seq (:interactive queues))) (boolean (seq (:bulk queues))) streak)]
        (cond
          closed? nil
          priority
          (let [[state key] (peek (get queues priority)) task (get-in @state [:tasks key])]
            (swap! scheduler #(-> % (update :pending dec)
                                  (update-in [:queues priority] pop)
                                  (assoc :streak (if (= priority :interactive) (inc streak) 0))))
            (swap! state assoc-in [:tasks key :thread] (Thread/currentThread))
            [state key task])
          :else (do (.wait ^Object scheduler) (recur)))))))

(defn- finished! [scheduler state key outcome]
  (locking scheduler
    (swap! state #(-> % (update :tasks dissoc key)
                      (update-in [:counts (if (:closed? %) :cancelled outcome)] inc)))
    (.notifyAll ^Object scheduler)))

(defn- work! [scheduler]
  (loop []
    ;; A cancelled owner's interrupt must not contaminate the next owner's work.
    (Thread/interrupted)
    (when-let [[state key {:keys [run! args]}] (take-next! scheduler)]
      (let [outcome (try
                      (let [result (apply run! args)] (if (= :failed (:state result)) :failed :completed))
                      (catch Throwable _ :failed))]
        (finished! scheduler state key outcome))
      (recur))))

(defn close!
  "Cancel pending jobs and interrupt/drain this owner's running bodies before resource closure."
  [{:keys [scheduler scopes state]}]
  (locking scheduler
    (swap! state assoc :closed? true)
    (let [tasks (:tasks @state) queued (remove (comp :thread val) tasks)]
      (swap! scheduler #(-> % (update :pending - (count queued))
                            (update :queues (fn [queues] (update-vals queues (fn [q] (into empty-queue (remove (fn [[owner _]] (identical? owner state)) q))))))))
      (swap! state #(-> % (update :tasks (fn [tasks] (apply dissoc tasks (map key queued))))
                        (update-in [:counts :cancelled] + (count queued))))
      (doseq [{:keys [thread]} (vals (:tasks @state))] (.interrupt ^Thread thread)))
    (.notifyAll ^Object scheduler)
    (let [deadline (+ (System/nanoTime) 30000000000)]
      (loop []
        (when (seq (:tasks @state))
          (let [remaining (- deadline (System/nanoTime))]
            (when-not (pos? remaining)
              (throw (ex-info "Background jobs did not stop; their resources remain open" {})))
            (.wait ^Object scheduler (long (max 1 (quot remaining 1000000))))
            (recur)))))
    (swap! scopes disj state))
  nil)

(defn reopen!
  "Resume a drained owner after failed import publication; never reuse running work."
  [{:keys [scheduler scopes state]}]
  (locking scheduler
    (when (or (:closed? @scheduler) (seq (:tasks @state)))
      (throw (ex-info "Cannot reopen an undrained background owner" {})))
    (swap! state assoc :closed? false)
    (swap! scopes conj state)))

(defmethod ig/init-key :shipyard.jobs/pool [_ options]
  (let [{:keys [threads] :as limits} (transforms/limits options)
        scheduler (atom {:closed? false :pending 0 :streak 0 :queues {:interactive empty-queue :bulk empty-queue}})
        counter (atom 0)
        factory (reify ThreadFactory
                  (newThread [_ task]
                    (doto (Thread. ^Runnable task (str "shipyard-job-" (swap! counter inc))) (.setDaemon true))))
        pool (ThreadPoolExecutor. (int threads) (int threads) 0 TimeUnit/MILLISECONDS
                                  (LinkedBlockingQueue.) ^ThreadFactory factory)]
    ;; Only these fixed worker loops enter the executor queue. User jobs reside
    ;; in the bounded scheduler and are never submitted as executor closures.
    (dotimes [_ threads] (.execute pool ^Runnable #(work! scheduler)))
    (assoc limits :scheduler scheduler :scopes (atom #{}) :pool pool)))

(defmethod ig/halt-key! :shipyard.jobs/pool [_ {:keys [scheduler ^ThreadPoolExecutor pool scopes] :as workers}]
  (locking scheduler (swap! scheduler assoc :closed? true) (.notifyAll ^Object scheduler))
  (doseq [state @scopes] (close! (assoc workers :state state)))
  (.shutdown pool)
  (when-not (.awaitTermination pool 30 TimeUnit/SECONDS)
    (throw (ex-info "Background workers did not stop; application resources remain open" {}))))
