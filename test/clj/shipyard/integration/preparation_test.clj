(ns shipyard.integration.preparation-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.library.index :as index]
            [shipyard.jobs :as jobs]
            [shipyard.mesh.cache :as cache]
            [shipyard.preparation :as preparation]
            [shipyard.preparation.routes :as routes]
            [shipyard.integration.thumbnail-cache-test :as previews])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest bounded-source-preparation-lifecycle
  (let [started (fixture/start! false fixture/library! fixture/author!
                                {:shipyard.preparation/service {:cap-bytes 1024 :max-entries 2}})
        sys (:system started) service (:shipyard.preparation/service sys)
        library (:shipyard.library/index sys) id (:hull fixture/ids)
        key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! library id)))
        entered (CountDownLatch. 1) release (CountDownLatch. 1) calls (atom 0)]
    (try
      (index/record-mesh-key! library id key nil)
      (let [descriptor {:key [:test 1 key] :part-id id :mesh-key key
                        :run! #(do (swap! calls inc) (.countDown entered) (.await release) {:value {:answer 42}})}
            job (preparation/request! service descriptor) resource (:resource job)]
        (testing "parallel requests deduplicate and HTTP callers return before computation"
          (is (= :running (:state job)))
          (is (.await entered 5 TimeUnit/SECONDS))
          (is (= resource (:resource (preparation/request! service descriptor))))
          (is (= 200 (:status (routes/status! {:preparation service} {:path-params {:resource resource}}))))
          (is (= 409 (:status (routes/data! {:preparation service} {:path-params {:resource resource}})))))
        (.countDown release)
        (previews/await! #(= :ready (:state (preparation/status! service resource))))
        (testing "ready data is reused and delivery checks the active library source"
          (is (= {:answer 42} (:value (preparation/request! service descriptor))))
          (is (= 1 @calls))
          (is (= 200 (:status (routes/data! {:preparation service} {:path-params {:resource resource}}))))
          (swap! (:state library) assoc :root "changed-root")
          (is (nil? (preparation/status! service resource)))
          (is (= 410 (:status (routes/data! {:preparation service} {:path-params {:resource resource}}))))))
      (finally (.countDown release) (fixture/stop! started)))))

(deftest parallel-preparation-revisions-eviction-and-cancellation
  (let [started (fixture/start! false fixture/library! fixture/author!
                                {:shipyard.preparation/service {:cap-bytes 1024 :max-entries 1}})
        sys (:system started) service (:shipyard.preparation/service sys)
        library (:shipyard.library/index sys) id (:hull fixture/ids)
        key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! library id)))
        entered (CountDownLatch. 2) release (CountDownLatch. 1) revision (atom 1)]
    (try
      (index/record-mesh-key! library id key nil)
      (let [descriptor (fn [feature] {:key [feature key] :part-id id :mesh-key key :valid?! #(= 1 @revision)
                                      :run! #(do (.countDown entered) (.await release) {:value feature})})
            a (preparation/request! service (descriptor :a)) b (preparation/request! service (descriptor :b))]
        (testing "independent feature jobs use both existing workers without blocking the caller"
          (is (.await entered 5 TimeUnit/SECONDS))
          (is (= :running (:state a))) (is (= :running (:state b))))
        (reset! revision 2)
        (is (nil? (preparation/status! service (:resource a))))
        (.countDown release)
        (previews/await! #(zero? (+ (:running (jobs/progress! (:scope service))) (:queued (jobs/progress! (:scope service))))))
        (is (empty? (:entries @(:state service))) "Invalidated work cannot publish after revision change"))
      (let [make! (fn [feature] (preparation/request! service {:key [feature key] :part-id id :mesh-key key :run! #(hash-map :value feature)}))
            old (make! :old)]
        (previews/await! #(= :ready (:state (preparation/status! service (:resource old)))))
        (let [new (make! :new)]
          (previews/await! #(= :ready (:state (preparation/status! service (:resource new)))))
          (is (nil? (preparation/status! service (:resource old))))
          (is (= 1 (count (:entries @(:state service)))))))
      (let [began (CountDownLatch. 1) job (preparation/request! service {:key [:cancel key] :part-id id :mesh-key key
                                                                         :retained-bytes 128
                                                                         :run! #(do (.countDown began) (Thread/sleep 10000) {:value :late})})]
        (is (.await began 5 TimeUnit/SECONDS))
        (preparation/close! service)
        (is (zero? (:retained-in-flight @(:state service))))
        (is (nil? (preparation/status! service (:resource job)))))
      (finally (.countDown release) (fixture/stop! started)))))

(deftest decoded-mesh-cache-accounts-for-jvm-residency
  (let [started (fixture/start! false fixture/library! fixture/author!
                                {:shipyard.preparation/service {:mesh-cap-bytes 1024}})
        sys (:system started) service (:shipyard.preparation/service sys)
        library (:shipyard.library/index sys) id (:hull fixture/ids)
        key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! library id)))]
    (try
      (let [mesh (preparation/read-mesh! service key 0)]
        (is (seq (:positions mesh)))
        (is (empty? (:entries @(:meshes service))) "A decoded boxed mesh exceeding the resident budget is not retained"))
      (finally (fixture/stop! started)))))

(deftest stale-producers-retain-admission-until-their-bodies-finish
  (let [started (fixture/start! false fixture/library! fixture/author!
                                {:shipyard.preparation/service {:cap-bytes 1024}})
        sys (:system started) service (:shipyard.preparation/service sys)
        library (:shipyard.library/index sys) id (:hull fixture/ids)
        key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! library id)))
        entered (CountDownLatch. 2) release (CountDownLatch. 1) revision (atom 1)
        descriptor (fn []
                     (let [expected @revision]
                       {:key [:reclaim key] :part-id id :mesh-key key :retained-bytes 400
                        :valid?! #(= expected @revision)
                        :run! #(do (.countDown entered) (.await release) {:value expected})}))]
    (try
      (index/record-mesh-key! library id key nil)
      (let [obsolete (preparation/request! service (descriptor))]
        (reset! revision 2)
        (is (nil? (preparation/status! service (:resource obsolete))))
        (let [replacement (preparation/request! service (descriptor))]
          (is (not= (:resource obsolete) (:resource replacement)))
          (is (.await entered 5 TimeUnit/SECONDS) "A replacement claim runs while the stale job for its cache key drains")
          (is (= 800 (:retained-in-flight @(:state service))))
          (reset! revision 3)
          (is (= :overloaded (:state (preparation/request! service (descriptor)))) "Pruning stale cache entries cannot reclaim captured job bytes")
          (.countDown release)
          (previews/await! #(zero? (+ (:running (jobs/progress! (:scope service))) (:queued (jobs/progress! (:scope service))))))
          (is (zero? (:retained-in-flight @(:state service))))
          (let [fresh (preparation/request! service (descriptor))]
            (previews/await! #(= :ready (:state (preparation/status! service (:resource fresh)))))
            (is (= 3 (:value (preparation/status! service (:resource fresh))))))))
      (preparation/close! service)
      (is (= :overloaded (:state (preparation/request! service (assoc (descriptor) :key [:rejected key])))))
      (is (zero? (:retained-in-flight @(:state service))) "Rejected submission releases its reservation")
      (finally (.countDown release) (fixture/stop! started)))))
