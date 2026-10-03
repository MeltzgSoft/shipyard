(ns shipyard.integration.paint-editor-test
  (:require [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.ship.db :as ships]
            [shipyard.persistence-fixture :as persisted]))

(deftest obsolete-selection-routes-cannot-write
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        store (:shipyard.ship/db sys) post #(handler (mock/request :post %1 %2))]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft)
      (lf/save-class! sys)
      (post "/assembly/paint" {})
      (post "/ships/paint/create" {:name "Customize"})
      (let [before (ships/snapshot! store)]
        (doseq [action ["material" "target" "default" "tool" "group/create" "group/rename" "group/delete" "group/members" "group/order"]]
          (is (= 404 (:status (post (str "/ships/paint/" action) {:base "#ff0000" :target "[]" :tool "select"})))))
        (is (= before (ships/snapshot! store)))
        (is (= before (persisted/records! store :ships))))
      (finally (fixture/stop! started)))))
