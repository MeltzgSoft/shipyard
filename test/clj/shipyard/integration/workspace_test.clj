(ns shipyard.integration.workspace-test
  (:require [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.operations :as operations]))

(deftest workspace-transitions-and-guards
  (let [started (fixture/start!) handler (:handler started) deps (lf/deps started)
        request (fn [uri mode generation]
                  (handler (-> (mock/request :get uri)
                               (mock/header "X-Shipyard-Workspace" mode)
                               (mock/header "X-Shipyard-Activation" (str generation)))))]
    (try
      (is (= 200 (:status (request "/workspace/orient" "orient" 1))))
      (is (= 200 (:status (request "/workspace/browse" "browse" 2))))
      (is (= 204 (:status (request "/orient/parts" "orient" 1))))
      (is (= 204 (:status (request "/orient/parts" "browse" 2))))
      (is (= 200 (:status (request "/workspace/orient" "orient" 3))))
      (is (= 204 (:status (request "/orient/parts" "orient" 1))))
      (swap! (get-in deps [:assembly :state]) assoc :draft lf/draft)
      (let [record (:loadout (operations/save! deps 1 "Saved cruiser"))
            result (handler (-> (mock/request :post "/ships/duplicate" {:id (str (:loadout/id record))})
                                (mock/header "X-Shipyard-Workspace" "ships")
                                (mock/header "X-Shipyard-Activation" "4")))]
        (is (= 200 (:status result)))
        (is (= "assembly" (get-in result [:headers "X-Shipyard-Destination"])))
        (is (re-find #"Saved cruiser - Copy" (:body result)))
        (is (nil? (get-in @(get-in deps [:assembly :state]) [:draft :loadout-id]))))
      (finally (fixture/stop! started)))))
