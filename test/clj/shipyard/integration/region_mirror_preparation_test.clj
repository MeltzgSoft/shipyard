(ns shipyard.integration.region-mirror-preparation-test
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

(deftest mirror-resource-is-source-bound
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        library (:shipyard.library/index sys) id (:weapon fixture/ids)]
    (try
      (jobs/submit! (:shipyard.http/jobs sys) id (index/fresh-source-file! library id))
      (loop [i 0] (when (and (< i 400) (nil? (index/mesh-key! library id))) (Thread/sleep 25) (recur (inc i))))
      (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection id)
      (let [mesh-key (index/mesh-key! library id)
            request (mock/request :get "/parts/regions/mirror" {:part-id id :mesh-key mesh-key :axis "x" :quaternion "[0 0 0 1]"})
            result (wait-ready! handler request)
            data-url (str "/preparation/" (:resource result) "/data")]
        (testing "Cold preparation and repeated requests return one numeric plane resource"
          (is (= :ready (:state result)))
          (is (= (:resource result) (:resource (wait-ready! handler request))))
          (let [data (edn/read-string (String. ^bytes (:body (handler (mock/request :get data-url))) "UTF-8"))]
            (is (zero? (:offset data)))
            (is (= [[-0.5 -0.5 -0.5] [0.5 0.5 0.5]] (:bounds data)))))
        (testing "A superseded axis/orientation request cannot claim an invalid orientation"
          (is (= 409 (:status (handler (mock/request :get "/parts/regions/mirror"
                                                     {:part-id id :mesh-key mesh-key :axis "z" :quaternion "[0 1 0 0]"}))))))
        (testing "Navigation invalidates feature admission"
          (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection (:weapon-alt fixture/ids))
          (is (= 409 (:status (handler request)))))
        (testing "Changed source invalidates cached numeric plane delivery"
          (spit (index/fresh-source-file! library id) "changed source")
          (is (= 410 (:status (handler (mock/request :get data-url)))))))
      (finally (fixture/stop! started)))))
