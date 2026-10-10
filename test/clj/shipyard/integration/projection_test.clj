(ns shipyard.integration.projection-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.projection :as projection]
            [shipyard.paint.projection-job :as job]
            [shipyard.preparation :as preparation]
            [shipyard.workspace.db :as workspace]
            [shipyard.integration.thumbnail-cache-test :as previews]))

(deftest binary-region-resource-lifecycle
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        service (:shipyard.preparation/service sys) library (:shipyard.library/index sys)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! library id)))]
    (try
      (index/record-mesh-key! library id key nil)
      (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection id)
      (let [keys (job/ordered-keys (preparation/read-mesh! service key 0))
            regions {:version 2 :mesh-key key :revision 1 :layers ["Primary" "Secondary"] :layer-definitions {}
                     :faces {(first keys) "Secondary"}}]
        (catalog/save-regions! cat id regions)
        (let [request #(handler (mock/request :get "/parts/regions/projection"
                                              {:part-id id :mesh-key key :revision %}))
              initial (request "1") envelope (edn/read-string (:body initial))]
          (is (= 200 (:status initial)))
          (previews/await! #(= :ready (:state (preparation/status! service (:resource envelope)))))
          (testing "actual Ring resource serves source-bound numeric masks without face keys"
            (let [response (handler (mock/request :get (str "/preparation/" (:resource envelope) "/data")))
                  decoded (projection/decode (:body response))]
              (is (= 200 (:status response)))
              (is (= "application/vnd.shipyard.appearance" (get-in response [:headers "content-type"])))
              (is (= key (:mesh-key decoded)))
              (is (= "1" (:region-revision decoded)))
              (is (= 1 (aget (:triangle-layers decoded) 0)))
              (is (not (str/includes? (String. ^bytes (:body response)) (first keys))))))
          (testing "same revision deduplicates and stale revision is rejected"
            (is (= (:resource envelope) (:resource (edn/read-string (:body (request "1"))))))
            (is (= 409 (:status (request "0")))))
          (testing "source invalidation expires resources rather than replaying stale masks"
            (swap! (:state library) assoc :root "changed-root")
            (is (= 410 (:status (handler (mock/request :get (str "/preparation/" (:resource envelope) "/data")))))))))
      (finally (fixture/stop! started)))))
