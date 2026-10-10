(ns shipyard.integration.region-surface-preparation-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.http.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.workspace.db :as workspace]))

(defn wait-ready! [handler request]
  (loop [attempt 0]
    (let [response (handler request) value (edn/read-string (:body response))]
      (if (and (= :running (:state value)) (< attempt 400))
        (do (Thread/sleep 25) (recur (inc attempt))) value))))

(deftest surface-resources-reuse-source-and-angle
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        library (:shipyard.library/index sys) id (:weapon fixture/ids)]
    (try
      (jobs/submit! (:shipyard.http/jobs sys) id (index/fresh-source-file! library id))
      (loop [i 0] (when (and (< i 400) (nil? (index/mesh-key! library id))) (Thread/sleep 25) (recur (inc i))))
      (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection id)
      (let [mesh-key (index/mesh-key! library id)
            request #(mock/request :get "/parts/regions/surfaces" {:part-id id :mesh-key mesh-key :angle (str %)})
            first-result (wait-ready! handler (request 1))
            second-result (wait-ready! handler (request 90))]
        (testing "Each angle retains its own compact binary resource"
          (is (= :ready (:state first-result) (:state second-result)))
          (is (not= (:resource first-result) (:resource second-result)))
          (is (= (:resource first-result) (:resource (wait-ready! handler (request 1)))))
          (is (= "application/octet-stream"
                 (get-in (handler (mock/request :get (str "/preparation/" (:resource first-result) "/data"))) [:headers "content-type"]))))
        (testing "Invalid tolerances and changed selection do not attach work"
          (is (= 409 (:status (handler (request 91)))))
          (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection (:weapon-alt fixture/ids))
          (is (= 409 (:status (handler (request 1))))))
        (testing "Changed source invalidates both ready delivery and renewed requests"
          (spit (index/fresh-source-file! library id) "changed source")
          (is (= 410 (:status (handler (mock/request :get (str "/preparation/" (:resource first-result) "/data"))))))))
      (finally (fixture/stop! started)))))
