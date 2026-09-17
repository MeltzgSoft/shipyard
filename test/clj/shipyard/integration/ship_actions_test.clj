(ns shipyard.integration.ship-actions-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.operations :as ops]
            [shipyard.loadout.db :as store]))

(deftest delete-route-validates-and-rejects-stale-workspaces
  (let [started (fixture/start!) deps (lf/deps started) handler (:handler started)
        state (get-in deps [:assembly :state])]
    (try
      (swap! state assoc :draft lf/draft)
      (let [record (:loadout (ops/save! deps 1 "Saved")) id (:loadout/id record)
            request (fn [mode generation value]
                      (handler (-> (mock/request :post "/ships/delete" {:id value})
                                   (mock/header "X-Shipyard-Workspace" mode)
                                   (mock/header "X-Shipyard-Activation" (str generation)))))]
        (handler (mock/request :get "/workspace/ships"))
        (is (= 204 (:status (request "ships" 0 (str id)))))
        (is (= 204 (:status (request "assembly" 1 (str id)))))
        (is (= record (get-in (store/snapshot! (:loadouts deps)) [:loadouts id])))
        (is (= 400 (:status (request "ships" 1 "invalid"))))
        (is (= 200 (:status (request "ships" 1 (str id)))))
        (is (nil? (get-in @state [:draft :loadout-id])))
        (let [before @state result (request "ships" 1 (str id))]
          (is (= 422 (:status result)))
          (is (str/includes? (:body result) "role=\"alert\""))
          (is (= before @state))))
      (finally (fixture/stop! started)))))

(deftest discard-confirmation-is-bound-to-current-draft
  (let [started (fixture/start!) deps (lf/deps started) handler (:handler started)
        state (get-in deps [:assembly :state])
        post (fn [path form] (handler (mock/request :post path form)))]
    (try
      (swap! state assoc :draft lf/draft)
      (let [record (:loadout (ops/save! deps 1 "Saved")) id (str (:loadout/id record))
            file (:file (:loadouts deps)) bytes (slurp (str file))]
        (swap! state assoc :draft (assoc lf/draft :revision 3))
        (doseq [action ["edit" "duplicate"]]
          (let [before @state response (post (str "/ships/" action) {:id id})]
            (is (str/includes? (:body response) "Discard unsaved assembly?"))
            (is (= before @state))
            (is (= 400 (:status (post (str "/ships/" action) {:id id :discard-revision "bad"}))))
            (swap! state update-in [:draft :revision] inc)
            (is (str/includes? (:body (post (str "/ships/" action) {:id id :discard-revision (str (get-in before [:draft :revision]))}))
                               "Discard unsaved assembly?"))
            (is (= (:hull lf/draft) (get-in @state [:draft :hull])))))
        (testing "acceptance of a fresh confirmation performs the transfer"
          (is (= 200 (:status (post "/ships/edit" {:id id :discard-revision (str (get-in @state [:draft :revision]))}))))
          (is (= (:loadout/id record) (get-in @state [:draft :loadout-id]))))
        (testing "a name-only edit is unsaved even before navigating away"
          (let [revision (str (get-in @state [:draft :revision]))
                params {:revision revision :part-id (:hull fixture/ids) :name "Unsaved rename"}
                response (post "/assembly/hull" params)]
            (is (str/includes? (:body response) "Discard unsaved assembly?"))
            (is (= lf/assignments (get-in @state [:draft :assignments])))
            (is (= "Unsaved rename" (get-in @state [:draft :name])))
            (is (= 200 (:status (post "/assembly/hull" (assoc params :discard-revision revision)))))
            (is (empty? (get-in @state [:draft :assignments])))
            (is (nil? (get-in @state [:draft :loadout-id])))))
        (is (= bytes (slurp (str file)))))
      (finally (fixture/stop! started)))))
