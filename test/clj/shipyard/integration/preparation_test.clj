(ns shipyard.integration.preparation-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.library.index :as index]
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
