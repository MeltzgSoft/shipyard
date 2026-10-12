(ns shipyard.integration.fleet-browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fleet.db :as fleet]
            [shipyard.loadout.db :as loadouts]))

(deftest fleet-navigation-and-admission
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        facade {:store (:shipyard.store/db sys) :catalog (:shipyard.catalog/db sys)}
        workspace (:state (:shipyard.workspace/db sys))
        request (fn [method url mode generation params]
                  (handler (-> (mock/request method url params)
                               (mock/header "HX-Request" "true")
                               (mock/header "X-Shipyard-Workspace" mode)
                               (mock/header "X-Shipyard-Activation" (str generation)))))]
    (try
      (let [created (:fleet (fleet/create! facade "Reserve")) id (str (:fleet/id created))]
        (testing "fleet table and editor retain their own navigation state"
          (is (= 200 (:status (request :get "/workspace/fleets" "browse" 0 {}))))
          (is (= 200 (:status (request :post "/fleets/open" "fleets" 1 {:id id :q "Res" :table-scroll "190"}))))
          (is (= :editor (get-in @workspace [:workspaces :fleets :view])))
          (is (= 200 (:status (request :get "/workspace/ships" "fleets" 1 {}))))
          (is (= 200 (:status (request :get "/workspace/fleets" "ships" 2 {}))))
          (is (= (:fleet/id created) (get-in @workspace [:workspaces :fleets :selection])))
          (let [response (request :get "/workspace/fleets?table=1" "fleets" 3 {})]
            (is (= 200 (:status response)))
            (is (str/includes? (:body response) "data-scroll-top=\"190\""))
            (is (str/includes? (:body response) "value=\"Res\""))))
        (testing "stale and foreign requests cannot enter another fleet or create a record"
          (is (= 204 (:status (request :post "/fleets/create" "fleets" 1 {:name "Stale"}))))
          (is (= 204 (:status (request :post "/fleets/create" "browse" 4 {:name "Foreign"}))))
          (is (= 1 (count (:fleets (fleet/snapshot! facade))))))
        (testing "new fleets enter the individual editor"
          (is (= 200 (:status (request :post "/fleets/create" "fleets" 4 {:name "Second"}))))
          (is (= 2 (count (:fleets (fleet/snapshot! facade)))))
          (is (= :editor (get-in @workspace [:workspaces :fleets :view])))))
      (testing "table totals aggregate large quantities without overflow"
        (let [class-id (random-uuid) other-class-id (random-uuid)
              db (:shipyard.loadout/db sys)]
          (doseq [id [class-id other-class-id]]
            (loadouts/put! db {:loadout/id id :loadout/name "Large class"
                               :loadout/hull (:hull fixture/ids) :loadout/slots {}} :create))
          (let [record (:fleet (fleet/create! facade "Reserve large"))]
            (fleet/put! facade (assoc record :fleet/entries {class-id Long/MAX_VALUE other-class-id Long/MAX_VALUE}) :update)
            (is (str/includes? (:body (request :get "/workspace/fleets?table=1" "fleets" 4 {}))
                               "18446744073709551614")))))
      (finally (fixture/stop! started)))))
