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

(deftest assembly-scroll-belongs-to-admitted-ship-workspace
  (let [started (fixture/start!) handler (:handler started)
        state (:state (:shipyard.workspace/db (:system started)))
        request (fn [mode generation scroll]
                  (handler (-> (mock/request :get "/assembly?poll=1")
                               (mock/header "HX-Request" "true")
                               (mock/header "X-Shipyard-Workspace" mode)
                               (mock/header "X-Shipyard-Activation" (str generation))
                               (mock/header "X-Shipyard-Assembly-Scroll" scroll))))]
    (try
      (handler (mock/request :get "/workspace/ships?tab=assembly"))
      (testing "the admitted offset is rendered and retained on later refreshes"
        (let [response (request "ships" 1 "320.5")]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "data-scroll-top=\"320.5\"")))
        (is (= 320.5 (get-in @state [:workspaces :ships :assembly-scroll])))
        (is (str/includes? (:body (handler (mock/request :get "/assembly?poll=1")))
                           "data-scroll-top=\"320.5\"")))
      (testing "stale and foreign workspace responses cannot change the retained offset"
        (is (= 204 (:status (request "ships" 0 "12"))))
        (is (= 204 (:status (request "browse" 1 "12"))))
        (is (= 320.5 (get-in @state [:workspaces :ships :assembly-scroll]))))
      (testing "invalid and unbounded offsets are ignored"
        (doseq [scroll ["invalid" "NaN" "Infinity" "-1" "100000001"]]
          (is (= 200 (:status (request "ships" 1 scroll)))))
        (is (= 320.5 (get-in @state [:workspaces :ships :assembly-scroll]))))
      (testing "a failed hull request keeps context; a successful new hull resets it"
        (let [failed (handler (mock/request :post "/assembly/hull" {:revision "0" :part-id "missing"}))
              successful (handler (mock/request :post "/assembly/hull" {:revision "0" :part-id (:hull fixture/ids)}))]
          (is (= 422 (:status failed)))
          (is (str/includes? (:body failed) "data-scroll-top=\"320.5\""))
          (is (= 200 (:status successful)))
          (is (str/includes? (:body successful) "data-scroll-top=\"0\""))
          (is (nil? (get-in @state [:workspaces :ships :assembly-scroll])))))
      (finally (fixture/stop! started)))))
