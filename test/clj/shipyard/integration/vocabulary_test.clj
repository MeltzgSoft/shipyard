(ns shipyard.integration.vocabulary-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.store.db :as store]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.vocabulary.db :as vocabulary]
            [shipyard.mount.wizard :as wizard]))

(deftest registered-values-survive-reopening
  (let [directory (fs/create-temp-dir)]
    (try
      (let [db (store/open! directory)]
        (try (vocabulary/add! db {:field :role :value "sensor-array"})
             (vocabulary/add! db {:field :role :value "sensor-array"})
             (finally (store/close! db))))
      (let [db (store/open! directory)]
        (try (is (contains? (:role (vocabulary/registered! db)) "sensor-array"))
             (finally (store/close! db))))
      (finally (fs/delete-tree directory)))))

(deftest shared-values-and-custom-role-authoring
  (let [started (fixture/start!) handler (:handler started) sys (:system started)
        cat (:shipyard.catalog/db sys) state (:state (:shipyard.workspace/db sys))
        post! #(handler (mock/request :post %1 %2))]
    (try
      (is (= 400 (:status (post! "/classifications" {"field" "wrong" "value" "Fleet"}))))
      (is (= 422 (:status (post! "/classifications" {"field" "role" "value" "bad/role"}))))
      (doseq [[field value] [["bundle" "Test Faction"] ["class" "Carrier"] ["role" "Sensor Array"]]]
        (let [response (post! "/classifications" {"field" field "value" value})]
          (is (= 200 (:status response)) (:body response))))
      (is (contains? (:bundle (vocabulary/choices! cat)) "Test Faction"))
      (is (contains? (:class (vocabulary/choices! cat)) "Carrier"))
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [(:prow fixture/ids) (:bridge fixture/ids)]))
      (is (= 200 (:status (post! "/parts/metadata" {"role" "Sensor Array"}))))
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat (:prow fixture/ids)))))
      (let [lib (:shipyard.library/index sys) id (:bridge fixture/ids)
            {:keys [mesh-key tris]} (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! lib id))]
        (index/record-mesh-key! lib id mesh-key tris))
      (is (= 200 (:status (post! "/parts/metadata/individual" {"part-id" (:bridge fixture/ids) "name" "bridge" "bundle" "Synthetic Navy" "class" "Cruiser" "role" "sensor-array"}))))
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat (:bridge fixture/ids)))))
      (is (some #(= #{:sensor-array} (:accepts %)) (wizard/acceptance-profiles :hull (vocabulary/roles! cat))))
      (let [result (wizard/save-request {"mount-id" "sensor" "kind" "socket" "accepts" "sensor-array" "action" "create"
                                         "frame" (pr-str fixture/frame)} [] [0 0 0 1] :hull)]
        (is (nil? (:error result)) (pr-str result))
        (is (= #{:sensor-array} (get-in result [:mount :mount/accepts]))))
      (finally (fixture/stop! started)))))
