(ns shipyard.integration.part-browser-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.part.orientation :as orientation]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]))

(deftest bulk-metadata-validation-and-atomicity
  (let [started (fixture/start!) handler (:handler started)
        a (:prow fixture/ids) b (:bridge fixture/ids)
        cat (:shipyard.catalog/db (:system started))
        state (get-in started [:system :shipyard.workspace/db :state])
        initial (catalog/snapshot! cat)
        post! #(handler (mock/request :post "/parts/metadata" %))]
    (try
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [a b]))
      (swap! state assoc-in [:workspaces :browse :filters] {"q" "prow" "class" "Cruiser"})
      (is (= 400 (:status (post! {"variant" "bad"}))))
      (is (= 422 (:status (post! {"name" " " "class" ""}))))
      (is (= 422 (:status (post! {"bundle" "Valid Fleet" "role" "bad/role"}))))
      (is (= initial (catalog/snapshot! cat)))
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [a "missing"]))
      (is (= 422 (:status (post! {"bundle" "New"}))))
      (is (= initial (catalog/snapshot! cat)))
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [a b]))
      (is (= 200 (:status (post! {"class" "New Class" "bundle" "New Fleet" "name" " " "role" ""}))))
      (is (= {"q" "prow" "class" "Cruiser"} (get-in @state [:workspaces :browse :filters])))
      (doseq [id [a b]]
        (let [before (catalog/part initial id)
              after (catalog/part (catalog/snapshot! cat) id)]
          (is (= "New Class" (:part/class after)))
          (is (= "New Fleet" (:part/bundle after)))
          (is (= (:part/name before) (:part/name after)))
          (is (= (:part/role-hint before) (:part/role-hint after)))
          (is (= (select-keys before [:part/id :part/uid :part/mounts :part/paint-regions :part/orientation])
                 (select-keys after [:part/id :part/uid :part/mounts :part/paint-regions :part/orientation])))))
      (finally (fixture/stop! started)))))

(deftest row-summaries-exclude-face-payloads
  (let [started (fixture/start!) cat (get-in started [:system :shipyard.catalog/db])
        id (:weapon fixture/ids) read! #(catalog/part (catalog/listing! cat) id)
        regions {:version 2 :layer-definitions {} :mesh-key (apply str (repeat 64 "a")) :revision 0 :layers ["Primary" "Secondary"]
                 :faces (zipmap (map #(format "%072x" %) (range 10000)) (repeat "Secondary"))}]
    (try
      (is (= {:plugs 1 :sockets {#{:turret} 1}} (:part/mount-summary (read!))))
      (is (false? (:part/has-regions? (read!))))
      (catalog/save-regions! cat id regions)
      (let [summary (read!)]
        (is (true? (:part/has-regions? summary)))
        (is (not (contains? summary :part/paint-regions)))
        (is (not (contains? summary :part/mounts)))
        (is (< (count (pr-str summary)) 2000)))
      (catalog/save-regions! cat id (assoc regions :faces {}))
      (is (false? (:part/has-regions? (read!))))
      (finally (fixture/stop! started)))))

(deftest drawer-edits-only-one-part-and-validates-all-fields
  (let [started (fixture/start!) handler (:handler started) cat (get-in started [:system :shipyard.catalog/db])
        state (get-in started [:system :shipyard.workspace/db :state])
        a (:prow fixture/ids) b (:bridge fixture/ids)
        original (catalog/snapshot! cat)
        params {"part-id" a "name" "Drawer Prow" "bundle" "Drawer Fleet" "class" "Drawer Cruiser" "role" "Sensor Array"}
        post! #(handler (mock/request :post "/parts/metadata/row" %))]
    (try
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [b]))
      (is (= 400 (:status (post! (dissoc params "class")))))
      (is (= 422 (:status (post! (assoc params "role" "bad/role")))))
      (is (= 422 (:status (post! (assoc params "part-id" "missing")))))
      (is (= original (catalog/snapshot! cat)))
      (is (= 200 (:status (post! params))))
      (let [after (catalog/snapshot! cat) part (catalog/part after a)]
        (is (= ["Drawer Prow" "Drawer Fleet" "Drawer Cruiser" :sensor-array]
               (mapv part [:part/name :part/bundle :part/class :part/role-hint])))
        (is (= (catalog/part original b) (catalog/part after b)))
        (is (= (select-keys (catalog/part original a) [:part/id :part/uid :part/mounts :part/paint-regions :part/orientation])
               (select-keys part [:part/id :part/uid :part/mounts :part/paint-regions :part/orientation])))
        (is (= (pr-str [b]) (get-in @state [:workspaces :browse :bulk-selection]))))
      (finally (fixture/stop! started)))))

(deftest drawer-orientation-and-labels-save-atomically
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        cat (:shipyard.catalog/db sys) id (:prow fixture/ids)
        before (catalog/snapshot! cat)
        params {"part-id" id "name" "Oriented Prow" "bundle" "Pose Fleet" "class" "Pose Cruiser" "role" "prow"
                "orientation-action" "save" "part-yaw-deg" "30" "part-pitch-deg" "-15" "part-roll-deg" "5.5"}
        post! #(handler (mock/request :post "/parts/metadata/row" %))]
    (try
      (is (= 400 (:status (post! (assoc params "orientation-action" "bad")))))
      (doseq [value ["NaN" "Infinity" "bad" "36001"]]
        (is (= 422 (:status (post! (assoc params "part-yaw-deg" value))))))
      (is (= 422 (:status (post! (dissoc params "part-roll-deg")))))
      (is (= 422 (:status (post! (assoc params "role" "bad/role")))))
      (is (= before (catalog/snapshot! cat)))
      (let [response (post! params) part (catalog/part (catalog/snapshot! cat) id)]
        (is (= 200 (:status response)))
        (is (= "Oriented Prow" (:part/name part)))
        (is (= (orientation/from-euler-degrees 30 -15 5.5) (:part/orientation part)))
        (is (str/includes? (:body response) "30.0°"))
        (is (= (select-keys (catalog/part before id) [:part/id :part/uid :part/mounts :part/paint-regions :part/source :part/variants])
               (select-keys part [:part/id :part/uid :part/mounts :part/paint-regions :part/source :part/variants]))))
      (let [pose (:part/orientation (catalog/summary! cat id))]
        (is (= 200 (:status (post! (assoc params "orientation-action" "keep" "name" "Renamed Prow" "part-yaw-deg" "0")))))
        (is (= pose (:part/orientation (catalog/summary! cat id))) "Untouched pose is preserved exactly"))
      (is (= 200 (:status (post! (merge params (zipmap ["part-yaw-deg" "part-pitch-deg" "part-roll-deg"] (repeat "0")))))))
      (is (= orientation/identity-quaternion (:part/orientation (catalog/summary! cat id))))
      (let [before (catalog/snapshot! cat)]
        (is (= 422 (:status (post! (assoc params "part-id" (:supported fixture/ids))))))
        (is (= before (catalog/snapshot! cat))))
      (finally (fixture/stop! started)))))

(deftest drawer-import-orientation-stays-staged-until-publication
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        cat (:shipyard.catalog/db sys) ws (:shipyard.workspace/db sys)
        before (catalog/snapshot! cat) directory (fs/create-temp-dir) zip (archives/archive! directory)
        post! #(handler (mock/request :post %1 %2))
        session! #(importer/session! {:workspace ws})
        params {"name" "Row Hull" "bundle" "Pose Fleet" "class" "Carrier" "role" "hull"
                "orientation-action" "save" "part-yaw-deg" "90" "part-pitch-deg" "0" "part-roll-deg" "0"}]
    (try
      (doseq [finish ["/imports/cancel" "/imports/commit"]]
        (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
        (let [staged (:catalog (session!))
              id (:part/id (first (filter #(= "Hull" (:part/name %)) (catalog/browse (catalog/listing! staged) {}))))]
          (is (= 200 (:status (post! "/parts/metadata/row" (assoc params "part-id" id)))))
          (is (= (orientation/from-euler-degrees 90 0 0) (:part/orientation (catalog/summary! staged id))))
          (is (= before (catalog/snapshot! cat)))
          (is (= 200 (:status (post! finish {}))))
          (if (= finish "/imports/cancel")
            (is (= before (catalog/snapshot! cat)))
            (is (= (orientation/from-euler-degrees 90 0 0)
                   (:part/orientation (catalog/summary! cat "Pose Fleet/Carrier/Row Hull")))))))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))

(deftest grouped-import-variant-error-rejects-labels-too
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        cat (:shipyard.catalog/db sys) ws (:shipyard.workspace/db sys)
        directory (fs/create-temp-dir) zip (archives/archive! directory)
        before (catalog/snapshot! cat)
        post! #(handler (mock/request :post %1 %2))]
    (try
      (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
      (let [session (importer/session! {:workspace ws}) staged (:catalog session)
            initial (catalog/snapshot! staged) entries @(:entries session)
            ids (mapv :part/id (catalog/browse (catalog/listing! staged) {}))]
        (swap! (:state ws) assoc-in [:workspaces :browse :bulk-selection] (pr-str ids))
        (is (= 422 (:status (post! "/parts/metadata" {"bundle" "Reviewed Fleet" "role" "hull" "variant" "supported"}))))
        (is (= entries @(:entries session)))
        (is (= initial (catalog/snapshot! staged)))
        (is (= before (catalog/snapshot! cat)))
        (is (= 200 (:status (post! "/parts/metadata" {"bundle" "Reviewed Fleet" "role" "hull" "name" " " "class" ""}))))
        (doseq [id ids]
          (let [part (catalog/summary! staged id) original (catalog/part initial id)]
            (is (= ["Reviewed Fleet" :hull] (mapv part [:part/bundle :part/role-hint])))
            (is (= (select-keys original [:part/name :part/class :part/id :part/uid :part/variants])
                   (select-keys part [:part/name :part/class :part/id :part/uid :part/variants])))))
        (is (= entries @(:entries session)))
        (is (= before (catalog/snapshot! cat))))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))
