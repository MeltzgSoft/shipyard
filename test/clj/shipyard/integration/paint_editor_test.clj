(ns shipyard.integration.paint-editor-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.ship.db :as schemes]))

(deftest independent-preview-and-ordered-commits
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        state (:state (:shipyard.assembly/db sys))
        paint (:shipyard.paint/db sys) store (:shipyard.ship/db sys)
        post (fn [uri form] (handler (mock/request :post uri form)))]
    (try
      (swap! state assoc :draft lf/draft)
      (is (= 200 (:status (do (lf/save-class! sys) (post "/assembly/paint" {})))))
      (is (= lf/assignments (get-in @(:state paint) [:draft :assignments])))
      (is (= lf/draft (dissoc (:draft @state) :name :loadout-id)))
      (is (= 200 (:status (post "/ships/paint/create" {:name "Independent"}))))
      (let [id (get-in @(:state paint) [:draft :ship-id])
            params {:id (str id) :target "[]" :sequence "1" :base "#ff0000" :metalness "0.2" :roughness "0.8"}
            response (post "/ships/paint/material" params)]
        (is (str/includes? (:body response) "Material saved"))
        (is (= [1.0 0.0 0.0] (get-in (persisted/records! store :ships)
                                     [:ships id :ship/paint :paint/instances [] :material :base])))
        (is (str/includes? (:body (post "/ships/paint/material" (assoc params :base "#0000ff"))) "selection changed"))
        (is (str/includes? (:body (post "/ships/paint/material" (assoc params :sequence "2" :metalness "NaN"))) "not saved"))
        (is (= 204 (:status (handler (-> (mock/request :post "/ships/paint/material" (assoc params :sequence "3"))
                                         (mock/header "X-Shipyard-Workspace" "ships")
                                         (mock/header "X-Shipyard-Activation" "0"))))))
        (is (= lf/draft (dissoc (:draft @state) :name :loadout-id)))
        (is (= 200 (:status (post "/ships/paint/rename" {:name "Renamed"}))))
        (is (= "Renamed" (get-in (schemes/snapshot! store) [:ships id :ship/name])))
        (post "/ships/paint/default" {})
        (is (empty? (get-in (schemes/snapshot! store) [:ships id :ship/paint :paint/instances]))))
      (finally (fixture/stop! started)))))

(deftest groups-membership-order-and-validation
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        paint (:shipyard.paint/db sys) store (:shipyard.ship/db sys)
        post (fn [uri form] (handler (mock/request :post uri form)))]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft)
      (lf/save-class! sys) (post "/assembly/paint" {})
      (post "/ships/paint/create" {:name "Groups"})
      (let [id (get-in @(:state paint) [:draft :ship-id])
            record #(get-in (schemes/snapshot! store) [:ships id :ship/paint])]
        (post "/ships/paint/group/create" {:name "One" :members ["[[:weapon 0]]" "[[:weapon 1]]"]})
        (let [group (first (:paint/groups (record))) gid (str (:group/id group))]
          (is (= 2 (count (:group/members group))))
          (is (= 400 (:status (post "/ships/paint/group/order" {:group gid :direction "sideways"}))))
          (is (str/includes? (:body (post "/ships/paint/group/members" {:group gid :members "not-present"})) "Choose instances"))
          (is (= 2 (count (:group/members (first (:paint/groups (record)))))))
          (post "/ships/paint/material" {:id (str id) :target (str "group/" gid) :sequence "1" :base "#ff0000" :metalness "0.7" :roughness "0.2"})
          (is (= [1.0 0.0 0.0] (:base (:group/material (first (:paint/groups (record)))))))
          (post "/ships/paint/group/create" {:name "Two" :members "[[:weapon 0]]"})
          (let [second-id (:group/id (second (:paint/groups (record))))]
            (post "/ships/paint/group/order" {:group (str second-id) :direction "up"})
            (is (= second-id (:group/id (first (:paint/groups (record)))))))
          (post "/ships/paint/group/rename" {:group gid :name "Renamed"})
          (is (= "Renamed" (:group/name (second (:paint/groups (record))))))
          (post "/ships/paint/group/members" {:group gid :members "[]"})
          (is (= [{:path [] :part-id (:hull fixture/ids)}] (:group/members (second (:paint/groups (record))))))
          (post "/ships/paint/group/delete" {:group gid})
          (is (= [0] (mapv :group/order (:paint/groups (record)))))
          (is (= (schemes/snapshot! store) (persisted/records! store :ships)))))
      (finally (fixture/stop! started)))))
