(ns shipyard.integration.inventory-picker-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.workspace.db :as workspace]))

(defn- token [response]
  (second (re-find #"name=\"token\"[^>]*value=\"([^\"]+)\"" (:body response))))

(deftest staged-inventory-is-atomic-cancellable-and-scoped-to-the-assembly
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        state (:state (:shipyard.assembly/db sys))
        workspaces (:shipyard.workspace/db sys)
        hull (:hull fixture/ids) gun (:weapon fixture/ids) turret (:turret fixture/ids)
        post! (fn [uri params] (handler (mock/request :post uri params)))
        open! #(handler (mock/request :get "/assembly/extras/open"))]
    (try
      (workspace/activate! workspaces :ships)
      (workspace/update-workspace! workspaces :browse assoc :filters {"q" "untouched"})
      (is (= 200 (:status (post! "/assembly/hull" {:revision "0" :part-id hull}))))
      (let [opened (open!) t (token opened) revision (get-in @state [:draft :revision])]
        (is (some? t))
        (is (str/includes? (:body opened) "data-inventory-part="))
        (is (not (str/includes? (:body opened) "part-drawer")))
        (is (= 200 (:status (post! "/assembly/extras/filter" {"token" t "q" "turret" (str "quantity." gun) "7"}))))
        (is (empty? (get-in @state [:draft :extras])))
        (is (= revision (get-in @state [:draft :revision])))
        (is (= 422 (:status (post! "/assembly/extras/add" {"token" t (str "quantity." turret) "-1"}))))
        (is (empty? (get-in @state [:draft :extras])))
        (is (= 200 (:status (post! "/assembly/extras/add" {"token" t "name" "Working name" (str "quantity." turret) "2"}))))
        (is (= {gun 7 turret 2} (get-in @state [:draft :extras])))
        (is (= "Working name" (get-in @state [:draft :name])))
        (is (= 1 (count (:scene @state)))))
      (testing "cancel discards staged quantities; reopening uses applied quantities"
        (let [t (token (open!))]
          (post! "/assembly/extras/filter" {"token" t (str "quantity." gun) "99"})
          (post! "/assembly/extras/cancel" {"token" t})
          (is (= 7 (get-in @state [:draft :extras gun]))))
        (let [opened (open!)]
          (is (str/includes? (:body opened) "value=\"7\""))
          (post! "/assembly/extras/add" {"token" (token opened) (str "quantity." gun) "0"})
          (is (= {turret 2} (get-in @state [:draft :extras])))))
      (testing "a stale dialog cannot overwrite a newer assembly revision"
        (let [t (token (open!)) revision (get-in @state [:draft :revision])]
          (post! "/assembly/extras" {:revision (str revision) :part-id gun :quantity "8"})
          (is (= 409 (:status (post! "/assembly/extras/add" {"token" t (str "quantity." gun) "1"}))))
          (is (= 8 (get-in @state [:draft :extras gun])))))
      (is (= {"q" "untouched"} (:filters (workspace/workspace! workspaces :browse))))
      (finally (fixture/stop! started)))))
