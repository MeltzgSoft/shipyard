(ns shipyard.integration.mount-preview-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.integration.thumbnail-cache-test :as previews]
            [shipyard.library.index :as index]
            [shipyard.jobs :as jobs]
            [shipyard.mesh.cache :as cache]
            [shipyard.mount.preview :as preview]
            [shipyard.mount.preview-wire :as wire]
            [shipyard.preparation :as preparation])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- request [value]
  (-> (mock/request :post "/mounts/preview")
      (mock/content-type "application/edn")
      (mock/body (pr-str value))))

(defn- ready! [service resource]
  (previews/await! #(not= :running (:state (preparation/status! service resource))))
  (preparation/status! service resource))

(deftest source-bound-draft-previews-are-derived-and-reusable
  (let [started (fixture/start! false fixture/library! (fn [_]))
        handler (:handler started) sys (:system started) cat (:shipyard.catalog/db sys)
        lib (:shipyard.library/index sys) service (:shipyard.preparation/service sys)
        id (:weapon fixture/ids) source (index/fresh-source-file! lib id)
        mesh-key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) source))
        _ (index/record-mesh-key! lib id mesh-key nil)
        input {:part-id id :mesh-key mesh-key :owner "test-preview" :sequence 1 :frame {:mount/pos [0.0 0.0 0.5]
                                                                                        :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]}
               :indices [0 1] :border-indices [0 1] :capacity 1 :direction :horizontal
               :cut {:kind :recess :depth 0.1 :border 0.0}}
        before (:part (catalog/part-context! cat id))]
    (try
      (testing "real HTTP resources return prepared typed lines without publishing authoring"
        (let [response (handler (request input)) resource (:resource (edn/read-string (:body response)))
              prepared (ready! service resource)
              data (handler (mock/request :get (str "/preparation/" resource "/data")))
              projection (wire/decode (:body data))]
          (is (= 200 (:status response)))
          (is (= :ready (:state prepared)))
          (is (= 72 (count (:cuts projection))))
          (is (= 30 (count (:border projection))))
          (is (= before (:part (catalog/part-context! cat id))))
          (is (not (fs/exists? (fs/path (.getParentFile source) "unsupported-pitted.stl"))))
          (is (= resource (:resource (edn/read-string (:body (handler (request input)))))))
          (let [next-resource (:resource (edn/read-string (:body (handler (request (assoc input :sequence 2))))))
                next-ready (ready! service next-resource)]
            (is (not= resource next-resource))
            (is (identical? (:bytes prepared) (:bytes next-ready)))
            (is (= 410 (:status (handler (mock/request :get (str "/preparation/" resource "/data"))))))
            (is (= 204 (:status (handler (-> (mock/request :post "/mounts/preview/cancel")
                                             (mock/content-type "application/edn")
                                             (mock/body (pr-str {:owner "test-preview" :sequence 3})))))))
            (is (= 410 (:status (handler (mock/request :get (str "/preparation/" next-resource "/data")))))))
          (fs/set-last-modified-time source (+ 1000 (fs/file-time->millis (fs/last-modified-time source))))
          (is (= 410 (:status (handler (mock/request :get (str "/preparation/" resource "/data"))))))))
      (testing "middleware rejects invalid indices, dimensions and frames"
        (doseq [value [(assoc input :indices []) (assoc-in input [:frame :mount/axis] [0.0 1.0])
                       (assoc-in input [:cut :border] -1.0) (assoc input :capacity 257)]]
          (is (= 400 (:status (handler (request value)))))))
      (finally (fixture/stop! started)))))

(deftest superseded-queued-drafts-skip-geometry-preparation
  (let [started (fixture/start! false fixture/library! (fn [_]))
        handler (:handler started) sys (:system started)
        lib (:shipyard.library/index sys) service (:shipyard.preparation/service sys)
        id (:weapon fixture/ids) source (index/fresh-source-file! lib id)
        mesh-key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) source))
        _ (index/record-mesh-key! lib id mesh-key nil)
        input {:part-id id :mesh-key mesh-key :owner "queued-draft" :sequence 1
               :frame {:mount/pos [0.0 0.0 0.5] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]}
               :indices [0 1] :capacity 1 :direction :horizontal
               :cut {:kind :recess :depth 0.1 :border 0.0}}
        scope (jobs/scope! (:shipyard.jobs/pool sys))
        entered (CountDownLatch. 2) release (CountDownLatch. 1)
        calls (atom 0) original preview/draft]
    (try
      (jobs/submit-batch! scope (mapv (fn [key] {:key key :run! #(do (.countDown entered) (.await release))}) [0 1]))
      (is (.await entered 5 TimeUnit/SECONDS))
      (with-redefs [preview/draft (fn [& args] (swap! calls inc) (apply original args))]
        (let [old-resource (:resource (edn/read-string (:body (handler (request input)))))
              resource (:resource (edn/read-string (:body (handler (request (-> input (assoc :sequence 2)
                                                                                (assoc-in [:cut :depth] 0.2)))))))]
          (.countDown release)
          (is (= :ready (:state (ready! service resource))))
          (is (= 410 (:status (handler (mock/request :get (str "/preparation/" old-resource "/data"))))))
          (previews/await! #(= {:running 0 :queued 0} (jobs/progress! (:scope service))))
          (is (= 1 @calls))))
      (finally (.countDown release) (jobs/close! scope) (fixture/stop! started)))))
