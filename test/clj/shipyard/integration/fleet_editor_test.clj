(ns shipyard.integration.fleet-editor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fleet.db :as fleet]
            [shipyard.loadout.db :as loadout]
            [shipyard.store.db :as store]
            [shipyard.workspace.db :as workspace]))

(defn class! [sys]
  (let [record {:loadout/id (random-uuid) :loadout/name "Cruiser class" :loadout/hull (:hull fixture/ids)
                :loadout/slots {[[:weapon 0]] (:weapon fixture/ids) [[:weapon 1]] (:weapon fixture/ids)}}]
    (loadout/put! (:shipyard.loadout/db sys) record :create)
    record))

(deftest fleet-editor-is-durable-and-preview-isolated
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        db (:shipyard.fleet/db sys) ws (:shipyard.workspace/db sys)
        class (class! sys) class-id (:loadout/id class)
        a (:fleet (fleet/create! db "First")) b (:fleet (fleet/create! db "Second")) id (:fleet/id a)
        post! #(handler (mock/request :post "/fleets/edit" (merge {"id" (str id) "action" %1} %2)))
        ship-assembly @(:state (:shipyard.assembly/db sys)) ship-preview @(:state (:shipyard.loadout.operations/preview sys))]
    (try
      (handler (mock/request :get "/workspace/fleets"))
      (handler (mock/request :post "/fleets/open" {"id" (str id)}))
      (is (= 200 (:status (post! "rename" {"name" "Updated"}))))
      (is (= "Updated" (:fleet/name (fleet/record! db id))))
      (is (= 200 (:status (post! "class-set" {"class-id" (str class-id) "quantity" "3"}))))
      (doseq [bad ["0" "-1" "1.5" "999999999999999999999"]]
        (is (= 422 (:status (post! "class-set" {"class-id" (str class-id) "quantity" bad})))))
      (is (= 3 (get-in (fleet/record! db id) [:fleet/entries class-id])))
      (is (= 200 (:status (post! "extra-set" {"part-id" (:weapon fixture/ids) "quantity" "4"}))))
      (let [response (handler (mock/request :get "/fleets"))]
        (is (str/includes? (:body response) "data-fleet-quantity=\"true\">10")))
      (is (= 200 (:status (handler (mock/request :post "/fleets/preview" {"id" (str id) "class-id" (str class-id)})))))
      (is (= class-id (get-in @(:state (:shipyard.fleet.handlers/preview sys)) [:draft :loadout-id])))
      (is (= ship-assembly @(:state (:shipyard.assembly/db sys))))
      (is (= ship-preview @(:state (:shipyard.loadout.operations/preview sys))))
      (is (= class (loadout/record! (:shipyard.loadout/db sys) class-id)))
      (post! "variant" {"variant" "supported" "direction" "up"})
      (is (= [:supported :unsupported :unsupported-pitted] (:fleet/variant-preference (fleet/record! db id))))
      (is (= (:fleet/variant-preference b) (:fleet/variant-preference (fleet/record! db (:fleet/id b)))))
      (store/write! (:shipyard.store/db sys) #(d/transact! % [{:store/key "shipyard" :settings/variant-preference [:unsupported-pitted :supported :unsupported]}]))
      (is (= [:supported :unsupported :unsupported-pitted] (:fleet/variant-preference (fleet/record! db id))))
      (post! "default" {})
      (is (= [:unsupported-pitted :supported :unsupported] (:fleet/variant-preference (fleet/record! db id))))
      (testing "stale list ids and poll ids preserve the current selection and other fleets"
        (is (= 409 (:status (handler (mock/request :post "/fleets/edit" {"id" (str (:fleet/id b)) "action" "rename" "name" "Wrong"})))))
        (is (= "Second" (:fleet/name (fleet/record! db (:fleet/id b)))))
        (is (= 409 (:status (handler (mock/request :post "/fleets/preview" {"id" (str (:fleet/id b)) "class-id" (str class-id)})))))
        (is (= class-id (:preview-class (workspace/workspace! ws :fleets))))
        (is (= 204 (:status (handler (mock/request :get "/fleets" {"poll" "1" "id" (str (:fleet/id b)) "class-id" (str class-id)})))))
        (is (= id (:selection (workspace/workspace! ws :fleets)))))
      (post! "extra-remove" {"part-id" (:weapon fixture/ids)})
      (is (empty? (:fleet/extras (fleet/record! db id))))
      (post! "class-remove" {"class-id" (str class-id)})
      (is (nil? (:preview-class (workspace/workspace! ws :fleets))))
      (is (empty? (:fleet/entries (fleet/record! db id))))
      (is (= 422 (:status (post! "delete" {}))))
      (is (fleet/record! db id))
      (is (= 200 (:status (post! "delete" {"confirm" "yes"}))))
      (is (nil? (fleet/record! db id)))
      (is (= :table (:view (workspace/workspace! ws :fleets))))
      (finally (fixture/stop! started)))))
