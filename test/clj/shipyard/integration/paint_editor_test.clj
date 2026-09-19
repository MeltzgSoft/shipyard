(ns shipyard.integration.paint-editor-test
  (:require [clojure.string :as str]
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
        (is (= [1.0 0.0 0.0] (get-in (schemes/snapshot! (schemes/open! (:file store)))
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
