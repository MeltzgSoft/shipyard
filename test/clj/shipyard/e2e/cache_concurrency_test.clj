(ns shipyard.e2e.cache-concurrency-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.e2e.support :as s]
            [shipyard.library.index :as index]
            [shipyard.part.orientation :as orientation])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest identical-meshes-load-with-independent-orientations
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver)
        system (:system started) inflight (:inflight (:shipyard.mesh/cache system))
        admitted (CountDownLatch. 2)
        a (:prow fixture/ids) b (:bridge fixture/ids)]
    (try
      (catalog/save-part-orientation! (:shipyard.catalog/db system) a
                                      (orientation/from-euler-degrees 45 0 0))
      (add-watch inflight ::admission
                 (fn [_ _ _ after]
                   (when (and (seq after) (pos? (.getCount admitted)))
                     (.countDown admitted)
                     (when-not (.await admitted 10 TimeUnit/SECONDS)
                       (throw (ex-info "Both cold mesh requests must overlap" {}))))))
      (s/go! driver (s/base-url system))
      (s/click! driver ".masthead__mode:text-is('Orient')")
      (s/wait-visible! driver "[data-bulk-select]")
      (doseq [id [a b]] (s/check! driver (str "[data-bulk-select][value='" id "']")))
      (s/click! driver "[data-bulk-render-button]")
      (when-not (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count])))
        (throw (ex-info "Both identical meshes must reach the real viewport" {})))
      (is (zero? (.getCount admitted)))
      (is (= 2 (s/count-els driver "[data-bulk-preview]")))
      (is (= (index/mesh-key! (:shipyard.library/index system) a)
             (index/mesh-key! (:shipyard.library/index system) b)))
      (let [poses (get-in (s/stats driver) [:bulk :orientations])]
        (is (not= (get poses (keyword a)) (get poses (keyword b)))))
      (s/fill-and-blur! driver "[data-bulk-angle][data-axis=y]" "90")
      (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :dirty]))))
      (s/click! driver "[data-bulk-save] button")
      (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
      (doseq [id [a b]]
        (let [q (:part/orientation (sidecar/read-sidecar! (str (:root started)) id))]
          (is (< (abs (- 90.0 (first (orientation/to-euler-degrees q)))) 0.001))))
      (finally (remove-watch inflight ::admission) (s/quit! driver) (fixture/stop! started)))))
