(ns shipyard.integration.part-regions-test
  (:require [shipyard.persistence-fixture :as persisted]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.regions.migration :as migration]
            [shipyard.region-fixture :as rf]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.http.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.paint.strokes :as strokes]
            [shipyard.scheme.db :as schemes]
            [shipyard.loadout.db :as loadouts]
            [shipyard.workspace.db :as workspace]))

(defn region-post [handler cat params]
  (handler (mock/request :post "/parts/regions"
                         (cond-> (merge {:layer-revision (str (:revision (catalog/region-registry (catalog/snapshot! cat))))} params)
                           (:layer params) (assoc :layer (or (rf/id cat (:layer params)) (:layer params)))))))

(deftest global-layer-deletion-is-confirmed-and-preserves-other-data
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        cat (:shipyard.catalog/db sys) root (str (:root started))
        ids [(:hull fixture/ids) (:weapon fixture/ids)]
        selected (:weapon-alt fixture/ids)
        mesh (apply str (repeat 64 "a")) key (apply str (repeat 72 "0")) other-key (apply str (repeat 72 "1"))
        post #(region-post handler cat %)
        params {:part-id selected :mesh-key mesh :revision "0" :action "delete" :layer "Trim" :confirmed "true"}
        read! #(mapv (fn [id] (persisted/authored! cat id)) ids)]
    (try
      (doseq [name ["Trim" "Running Lights"]]
        (catalog/edit-region-layer! cat selected 0 (:revision (catalog/region-registry (catalog/snapshot! cat))) "add" nil name))
      (doseq [id ids]
        (catalog/save-regions! cat id
                               {:version 2 :mesh-key mesh :revision 5
                                :layers ["Primary" "Secondary" (rf/id cat "Trim") (rf/id cat "Running Lights")]
                                :layer-definitions (:layers (catalog/region-registry (catalog/snapshot! cat)))
                                :faces {key (rf/id cat "Trim") other-key "Secondary"}}))
      (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection selected)
      (let [before (read!)]
        (is (str/includes? (:body (post (dissoc params :confirmed))) "Confirm deleting"))
        (is (str/includes? (:body (post (assoc params :revision "1"))) "Regions changed"))
        (doseq [layer ["Primary" "Secondary" "Missing"]]
          (is (str/includes? (:body (post (assoc params :layer layer))) "Choose an existing detail layer")))
        (is (= before (read!)))

        (is (= 200 (:status (post params))))
        (doseq [[old new] (map vector before (read!))]
          (is (= (dissoc old :part/paint-regions) (dissoc new :part/paint-regions)))
          (is (= {:revision 6 :faces {other-key "Secondary"}}
                 (select-keys (:part/paint-regions new) [:revision :faces]))))
        (catalog/reingest! cat (index/parts! (:shipyard.library/index sys)) root)
        (is (= ["Primary" "Secondary" "Running Lights"] (rf/names cat)))
        (is (nil? (catalog/part-regions (catalog/part (catalog/snapshot! cat) selected)))))
      (finally (fixture/stop! started)))))

(deftest regions-roundtrip-and-source-guards
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        id (:weapon fixture/ids) library (:shipyard.library/index sys) cat (:shipyard.catalog/db sys)
        post #(region-post handler cat %)]
    (try
      (jobs/submit! (:shipyard.http/jobs sys) id (index/fresh-source-file! library id))
      (loop [attempt 0]
        (when (and (< attempt 200) (not (index/mesh-key! library id))) (Thread/sleep 25) (recur (inc attempt))))
      (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection id)
      (let [mesh (index/mesh-key! library id)
            key (first (strokes/known-faces! {:cache (:shipyard.mesh/cache sys) :paint (:shipyard.paint/db sys)} mesh))
            params {:part-id id :mesh-key mesh :revision "0" :action "assign" :layer "Secondary" :faces (pr-str [key])}
            read! #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
        (is (= 400 (:status (post (assoc params :action "invalid")))))
        (is (= 200 (:status (post params))))
        (is (= "Secondary" (get-in (read!) [:faces key])))
        (is (= (read!) (:part/paint-regions (persisted/authored! cat id))))
        (catalog/reingest! cat (index/parts! library) (str (:root started)))
        (is (= "Secondary" (get-in (read!) [:faces key])))
        (let [before (read!)]
          (is (str/includes? (:body (post params)) "Regions changed"))
          (is (str/includes? (:body (post (assoc params :revision "1" :faces (pr-str [(apply str (repeat 72 "f"))])))) "Invalid region faces"))
          (is (str/includes? (:body (post (assoc params :revision "1" :mesh-key (apply str (repeat 64 "b"))))) "Source changed"))
          (is (= before (read!))))
        (post (assoc params :revision "1" :action "add" :name "Trim"))
        (is (= ["Primary" "Secondary" "Trim"] (rf/names cat)))
        (is (= ["Primary" "Secondary" "Trim"] (rf/names cat)))
        (catalog/reingest! cat (index/parts! library) (str (:root started)))
        (is (= ["Primary" "Secondary" "Trim"] (rf/names cat)))
        (post (assoc params :revision "1" :action "fill" :layer "Trim" :faces "[]"))
        (is (= 12 (count (:faces (read!)))))
        (is (false? (:colors (workspace/workspace! (:shipyard.workspace/db sys) :browse))))
        (is (= #{(rf/id cat "Trim")} (set (vals (:faces (read!))))))
        (is (str/includes? (:body (post (assoc params :revision "1" :action "fill"))) "Regions changed"))
        (is (str/includes? (:body (post (assoc params :revision "2" :action "fill" :mesh-key (apply str (repeat 64 "b"))))) "Source changed"))
        (post (assoc params :revision "2" :action "fill" :layer "Primary"))
        (is (empty? (:faces (read!))))
        (is (= ["Primary" "Secondary" "Trim"] (rf/names cat)))
        (post (assoc params :revision "3" :action "reset"))
        (is (empty? (:faces (read!)))))
      (finally (fixture/stop! started)))))

(deftest scheme-delete-preserves-references
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        post #(handler (mock/request :post %1 %2)) store (:shipyard.scheme/db sys)]
    (try
      (post "/paint/create" {:name "Shared"})
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            ship {:loadout/id (random-uuid) :loadout/name "Reference ship" :loadout/hull (:hull fixture/ids) :loadout/slots {} :loadout/scheme id}]
        (loadouts/put! (:shipyard.loadout/db sys) ship :create)
        (is (str/includes? (:body (handler (mock/request :get "/paint"))) (:loadout/name ship)))
        (post "/paint/delete" {:id (str id)})
        (is (get-in (schemes/snapshot! store) [:schemes id]))

        (post "/paint/delete" {:id (str id) :confirmed "true"})
        (is (empty? (:schemes (persisted/records! store :schemes))))
        (is (= id (get-in (loadouts/snapshot! (:shipyard.loadout/db sys)) [:loadouts (:loadout/id ship) :loadout/scheme])))
        (is (nil? (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme]))))
      (finally (fixture/stop! started)))))

(deftest shared-identities-migrate-and-edit-from-an-unused-part
  (let [mesh (apply str (repeat 64 "a")) face (apply str (repeat 72 "0"))
        legacy {:mesh-key mesh :revision 4 :layers ["Primary" "Secondary" "Trim"] :faces {face "Trim"}}
        scheme-id (random-uuid) material {:base [0.2 0.3 0.4] :metalness 0.7 :roughness 0.2}
        started (fixture/start!
                 false (fn [root]
                         (fixture/library! root)
                         (doseq [id [(:weapon fixture/ids) (:weapon-alt fixture/ids)]]
                           (sidecar/update-sidecar! (str root) id assoc :part/paint-regions legacy))
                         (let [file (fs/path (fs/parent root) "data" "shipyard" "schemes.edn")]
                           (fs/create-dirs (fs/parent file))
                           (spit (str file) (pr-str {:version 1 :schemes {scheme-id {:scheme/id scheme-id :scheme/name "Legacy"
                                                                                     :scheme/roles {} :scheme/layers {"Trim" material}}}})))
                         root))
        sys (:system started) handler (:handler started) cat (:shipyard.catalog/db sys)
        store (:shipyard.scheme/db sys) root (str (:root started))
        selected (:hull fixture/ids) layer (migration/legacy-id "Trim")
        files (mapv #(sidecar/sidecar-file root %) [(:weapon fixture/ids) (:weapon-alt fixture/ids)])
        read! #(mapv slurp files)
        params {:part-id selected :mesh-key mesh :revision "0" :action "rename" :layer layer :name "Accent"}
        post #(region-post handler cat %)]
    (try
      (workspace/update-workspace! (:shipyard.workspace/db sys) :browse assoc :selection selected)
      (let [before (read!) palette (schemes/snapshot! store)]
        (is (= layer (rf/id cat "Trim")))
        (is (= material (get-in palette [:schemes scheme-id :scheme/layers layer])))
        (is (= 200 (:status (post params))))
        (is (= layer (rf/id cat "Accent")))
        (is (nil? (rf/id cat "Trim")))
        (is (= before (read!)) "Renaming an entity never rewrites its face assignments")
        (is (= palette (persisted/records! store :schemes)) "Legacy palettes resolve to the same IDs on every restart")
        (schemes/put! store (get-in palette [:schemes scheme-id]) :update)
        (is (= material (get-in (schemes/snapshot! store) [:schemes scheme-id :scheme/layers layer])))
        (is (= palette (persisted/records! store :schemes)))
        (is (nil? (catalog/part-regions (catalog/part (catalog/snapshot! cat) selected))))
        (catalog/reingest! cat (index/parts! (:shipyard.library/index sys)) root)
        (is (= layer (rf/id cat "Accent")))
        (is (str/includes? (:body (post (assoc params :layer-revision "0" :name "Stale"))) "Layers changed"))
        (is (str/includes? (:body (post (assoc params :name "Secondary"))) "unique layer name"))
        (doseq [angle ["-1" "91" "1.5" "NaN"]]
          (is (= 400 (:status (post (assoc params :angle angle))))))

        (post (assoc params :action "add" :name "Unused"))
        (let [unused (rf/id cat "Unused")]
          (is (some? unused))
          (post (assoc params :layer unused :name "Spare"))
          (catalog/reingest! cat (index/parts! (:shipyard.library/index sys)) root)
          (is (= unused (rf/id cat "Spare")))
          (is (= before (read!)))
          (post (assoc params :action "delete" :layer unused :confirmed "true"))
          (is (nil? (rf/id cat "Spare"))))
        (post (assoc params :action "delete" :confirmed "true"))
        (doseq [id [(:weapon fixture/ids) (:weapon-alt fixture/ids)]]
          (let [region (:part/paint-regions (persisted/authored! cat id))]
            (is (= 2 (:version region)))
            (is (empty? (:faces region)))))
        (is (= palette (schemes/snapshot! store)))
        (catalog/reingest! cat (index/parts! (:shipyard.library/index sys)) root)
        (is (= ["Primary" "Secondary"] (rf/names cat))))
      (finally (fixture/stop! started)))))
