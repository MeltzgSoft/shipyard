(ns shipyard.integration.part-metadata-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.vocabulary.db :as vocabulary]
            [shipyard.workspace.db :as workspace]))

(deftest individual-metadata-shares-atomic-validation-and-preserves-authored-data
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids) lib (:shipyard.library/index sys)
        params {"part-id" id "name" "Named Battery" "bundle" "Shared Navy" "class" "Carrier" "role" "Sensor Array"}
        post! #(handler (mock/request :post "/parts/metadata/individual" %))]
    (try
      (let [{:keys [mesh-key tris]} (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! lib id))]
        (index/record-mesh-key! lib id mesh-key tris))
      (catalog/save-part-orientation! cat id [0.0 1.0 0.0 0.0])
      (let [before (catalog/snapshot! cat)]
        (is (= 400 (:status (post! (dissoc params "class")))))
        (doseq [invalid [(assoc params "name" " ") (assoc params "bundle" "../bad") (assoc params "role" "bad/role")]]
          (is (= 422 (:status (post! invalid)))))
        (is (= before (catalog/snapshot! cat)))
        (let [response (post! (assoc params "orientation-action" "save" "part-yaw-deg" "0" "part-pitch-deg" "0" "part-roll-deg" "0"))
              part (catalog/summary! cat id)]
          (is (= 200 (:status response)))
          (is (= ["Named Battery" "Shared Navy" "Carrier" :sensor-array]
                 (mapv part [:part/name :part/bundle :part/class :part/role-hint])))
          (is (= (select-keys (catalog/part before id) [:part/id :part/uid :part/mounts :part/orientation :part/source :part/paint-regions])
                 (select-keys (catalog/part (catalog/snapshot! cat) id) [:part/id :part/uid :part/mounts :part/orientation :part/source :part/paint-regions])))
          (is (str/includes? (:body response) "Named Battery"))
          (is (str/includes? (:body response) "hx-preserve=\"true\""))
          (is (str/includes? (:body response) "classification-values"))
          (is (str/includes? (:body response) "Saved."))
          (is (= "Named Battery" (get-in (persisted/catalog! cat) [:parts id :part/name])))
          (is (contains? (:class (vocabulary/choices! cat)) "Carrier"))))
      (let [supported (:supported fixture/ids)
            response (post! (assoc params "part-id" supported "name" "Supported Battery"))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Supported Battery"))
        (is (str/includes? (:body response) "Save metadata"))
        (is (str/includes? (:body response) "Saved."))
        (is (= "Supported Battery" (:part/name (catalog/summary! cat supported)))))
      (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :import {:active true})
      (is (= 409 (:status (post! params))))
      (is (= 404 (:status (handler (mock/request :post "/parts/role" {:part-id id :part-role "hull"})))))
      (finally (workspace/update-workspace! (:shipyard.workspace/db sys) :browse dissoc :import) (fixture/stop! started)))))
