(ns shipyard.integration.paint-editor-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.scheme.db :as schemes]))

(deftest independent-preview-and-ordered-commits
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        state (:state (:shipyard.assembly/db sys))
        paint (:shipyard.paint/db sys) store (:shipyard.scheme/db sys)
        post (fn [uri form] (handler (mock/request :post uri form)))]
    (try
      (swap! state assoc :draft lf/draft)
      (is (= 200 (:status (post "/assembly/paint" {}))))
      (is (= lf/assignments (get-in @(:state paint) [:draft :assignments])))
      (is (= lf/draft (:draft @state)))
      (is (= 200 (:status (post "/paint/create" {:name "Independent"}))))
      (let [id (get-in @(:state paint) [:draft :scheme])
            params {:id (str id) :target "[]" :sequence "1" :base "#ff0000" :metalness "0.2" :roughness "0.8"}
            response (post "/paint/material" params)]
        (is (str/includes? (:body response) "Material saved"))
        (is (= [1.0 0.0 0.0] (get-in (persisted/records! store :schemes)
                                     [:schemes id :scheme/instances [] :material :base])))
        (is (str/includes? (:body (post "/paint/material" (assoc params :base "#0000ff"))) "selection changed"))
        (is (str/includes? (:body (post "/paint/material" (assoc params :sequence "2" :metalness "NaN"))) "not saved"))
        (is (= 204 (:status (handler (-> (mock/request :post "/paint/material" (assoc params :sequence "3"))
                                         (mock/header "X-Shipyard-Workspace" "paint")
                                         (mock/header "X-Shipyard-Activation" "0"))))))
        (is (= lf/draft (:draft @state)))
        (is (= 200 (:status (post "/paint/rename" {:name "Renamed"}))))
        (is (= "Renamed" (get-in (schemes/snapshot! store) [:schemes id :scheme/name])))
        (post "/paint/default" {})
        (is (empty? (get-in (schemes/snapshot! store) [:schemes id :scheme/instances]))))
      (finally (fixture/stop! started)))))

(deftest groups-membership-order-and-validation
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        paint (:shipyard.paint/db sys) store (:shipyard.scheme/db sys)
        post (fn [uri form] (handler (mock/request :post uri form)))]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft)
      (post "/assembly/paint" {})
      (post "/paint/create" {:name "Groups"})
      (let [id (get-in @(:state paint) [:draft :scheme])
            record #(get-in (schemes/snapshot! store) [:schemes id])]
        (post "/paint/group/create" {:name "One" :members ["[[:weapon 0]]" "[[:weapon 1]]"]})
        (let [group (first (:scheme/groups (record))) gid (str (:group/id group))]
          (is (= 2 (count (:group/members group))))
          (is (= 400 (:status (post "/paint/group/order" {:group gid :direction "sideways"}))))
          (is (str/includes? (:body (post "/paint/group/members" {:group gid :members "not-present"})) "Choose instances"))
          (is (= 2 (count (:group/members (first (:scheme/groups (record)))))))
          (post "/paint/material" {:id (str id) :target (str "group/" gid) :sequence "1" :base "#ff0000" :metalness "0.7" :roughness "0.2"})
          (is (= [1.0 0.0 0.0] (:base (:group/material (first (:scheme/groups (record)))))))
          (post "/paint/group/create" {:name "Two" :members "[[:weapon 0]]"})
          (let [second-id (:group/id (second (:scheme/groups (record))))]
            (post "/paint/group/order" {:group (str second-id) :direction "up"})
            (is (= second-id (:group/id (first (:scheme/groups (record)))))))
          (post "/paint/group/rename" {:group gid :name "Renamed"})
          (is (= "Renamed" (:group/name (second (:scheme/groups (record))))))
          (post "/paint/group/members" {:group gid :members "[]"})
          (is (= [{:path [] :part-id (:hull fixture/ids)}] (:group/members (second (:scheme/groups (record))))))
          (post "/paint/group/delete" {:group gid})
          (is (= [0] (mapv :group/order (:scheme/groups (record)))))
          (is (= (schemes/snapshot! store) (persisted/records! store :schemes)))))
      (finally (fixture/stop! started)))))
