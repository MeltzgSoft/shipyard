(ns shipyard.integration.workspace-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.operations :as operations]))

(deftest workspace-transitions-and-guards
  (let [started (fixture/start!) handler (:handler started) deps (lf/deps started)
        state (:state (:shipyard.workspace/db (:system started)))
        request (fn [method uri mode generation params]
                  (handler (-> (mock/request method uri params)
                               (mock/header "HX-Request" "true")
                               (mock/header "X-Shipyard-Workspace" mode)
                               (mock/header "X-Shipyard-Activation" (str generation)))))]
    (try
      (testing "Ship Browser starts with its own colors disabled"
        (is (false? (get-in @state [:workspaces :ships :colors])))
        (is (nil? (get-in @state [:workspaces :assembly]))))
      (testing "only the server advances the activation"
        (is (= 204 (:status (request :get "/workspace/browse" "browse" 999 {}))))
        (is (= [:browse 0] ((juxt :active :activation) @state)))
        (is (= 200 (:status (request :get "/workspace/browse" "browse" 0 {}))))
        (is (= [:browse 1] ((juxt :active :activation) @state)))
        (is (= 200 (:status (request :get "/workspace/browse" "browse" 1 {}))))
        (is (= 204 (:status (request :get "/orient/parts" "browse" 1 {}))))
        (is (= 200 (:status (request :get "/orient/parts" "browse" 2 {}))))
        (is (= 200 (:status (request :get "/workspace/browse" "browse" 2 {}))))
        (is (= 204 (:status (request :get "/orient/parts" "browse" 1 {})))))
      (testing "selection, filters and display settings belong to the backend"
        (is (= 200 (:status (request :post "/orient/selection" "browse" 3
                                     {:visible (pr-str [(:prow fixture/ids)]) :selected (:prow fixture/ids)}))))
        (is (= (pr-str [(:prow fixture/ids)]) (get-in @state [:workspaces :browse :bulk-selection])))
        (is (= 200 (:status (request :post "/workspace/display/colors" "browse" 3 {}))))
        (is (false? (get-in @state [:workspaces :browse :colors])))
        (is (= 400 (:status (request :post "/orient/selection" "browse" 3 {}))))
        (is (= 400 (:status (request :get "/workspace/invalid" "browse" 3 {}))))
        (is (= [:browse 3] ((juxt :active :activation) @state))))
      (swap! (get-in deps [:assembly :state]) assoc :draft lf/draft)
      (let [record (:loadout (operations/save! deps 1 "Saved cruiser"))]
        (is (= 200 (:status (request :get "/workspace/ships" "browse" 3 {}))))
        (let [result (request :post "/ships/duplicate" "ships" 4 {:id (str (:loadout/id record))})]
          (is (= 200 (:status result)))
          (is (= [:ships 5] ((juxt :active :activation) @state)))
          (is (re-find #"Saved cruiser - Copy" (:body result)))
          (is (str/includes? (:body result) "data-workspace=\"ships\""))
          (is (nil? (get-in @(get-in deps [:assembly :state]) [:draft :loadout-id])))))
      (testing "an ordinary workspace URL returns a page with server context"
        (let [response (handler (mock/request :get "/workspace/browse"))]
          (is (= 200 (:status response)))
          (is (str/starts-with? (:body response) "<!DOCTYPE html>"))
          (is (not (str/includes? (:body response) "/js/workspace.js")))
          (is (str/includes? (:body response) "data-mount-colors=\"false\""))))
      (testing "retired workspace aliases are rejected"
        (doseq [url ["/workspace/orient" "/workspace/assembly" "/ships/tab/class"]]
          (is (= 400 (:status (handler (mock/request :get url))))))
        (is (= 404 (:status (handler (mock/request :post "/ships/preview" {:id (str (random-uuid))}))))))
      (finally (fixture/stop! started)))))
