(ns shipyard.integration.importer-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [datalevin.core :as d]
            [shipyard.store.db :as store]
            [shipyard.fixtures :as meshes]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.importer.archive :as archive]
            [shipyard.library.index :as index]
            [shipyard.vocabulary.db :as vocabulary]))

(deftest review-edit-publish-and-cancel
  (let [started (fixture/start!) system (:system started) handler (:handler started)
        workspace (:shipyard.workspace/db system) lib (:shipyard.library/index system)
        cat (:shipyard.catalog/db system) directory (fs/create-temp-dir)
        zip (archives/archive! directory) session! #(importer/session! {:workspace workspace})
        post! #(handler (mock/request :post %1 %2))
        before (catalog/listing! cat)]
    (try
      (testing "recursive archive review does not change the durable library"
        (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
        (is (= 3 (count @(:entries (session!)))))
        (is (= [["New Fleet.zip" "Cruisers Supported(1).zip"]] (:skipped-empty-archives (session!))))
        (is (= 200 (:status (post! "/imports/selection" {"selection" "all"}))))
        (is (= 2 (count (edn/read-string (get-in @(:state workspace) [:workspaces :browse :bulk-selection])))))
        (is (= before (catalog/listing! cat)))
        (is (= 409 (:status (post! "/mounts" {}))))
        (is (= 409 (:status (post! "/parts/regions" {}))))
        (is (= 409 (:status (post! "/settings" {"root" "/tmp"})))))
      (let [session (session!) parts (vals (:parts (catalog/listing! (:catalog session))))
            ids (mapv :part/id parts)
            unsupported (filter :part/renderable parts)
            orientations (into {} (map (fn [p] [(:part/id p) [0 1 0 0]]) unsupported))]
        (testing "bulk metadata and orientations stay in the review until commit"
          (is (= 200 (:status (post! "/orient/selection" {"visible" (pr-str ids) "selected" ids}))))
          (is (= 200 (:status (post! "/parts/metadata" {"field" "bundle" "operation" "set" "value" "Imported Fleet"}))))
          (is (= 200 (:status (post! "/orient/save" {"orientations" (pr-str orientations)}))))
          (is (= before (catalog/listing! cat))))
        (testing "canonical filenames, grouping and saved poses survive a rescan"
          (let [response (post! "/imports/commit" {})]
            (is (= 200 (:status response)) (:body response)))
          (is (nil? (session!)))
          (doseq [path ["Imported Fleet/Cruiser/Hull/unsupported.stl"
                        "Imported Fleet/Cruiser/Hull/supported.stl"
                        "Imported Fleet/Cruiser/Prow/unsupported.stl"]]
            (is (fs/regular-file? (fs/path (index/root! lib) path))))
          (catalog/reingest! cat (index/parts! lib) (index/root! lib))
          (is (= [0.0 1.0 0.0 0.0] (:part/orientation (catalog/part (catalog/listing! cat) "Imported Fleet/Cruiser/Hull"))))
          (is (fs/regular-file? zip))))
      (testing "cancel removes temporary data and preserves the library"
        (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
        (let [temporary (:directory (session!)) snapshot (catalog/listing! cat)]
          (is (= 200 (:status (post! "/imports/cancel" {}))))
          (is (not (fs/exists? temporary)))
          (is (= snapshot (catalog/listing! cat)))))
      (testing "existing targets refuse commit and keep a retryable review"
        (post! "/imports/start" {"archive" (str zip)})
        (is (= 200 (:status (post! "/imports/commit" {}))))
        (post! "/imports/start" {"archive" (str zip)})
        (is (= 422 (:status (post! "/imports/commit" {}))))
        (is (some? (session!))))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))

(deftest later-imports-use-current-shared-values
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        workspace (:shipyard.workspace/db sys) cat (:shipyard.catalog/db sys)
        database (:shipyard.store/db sys) directory (fs/create-temp-dir)
        zip (fs/file directory "Downloads.zip") data (meshes/->binary-stl (meshes/cube))
        session! #(importer/session! {:workspace workspace})
        parts! #(catalog/browse (catalog/listing! (:catalog (session!))) {})
        post! #(handler (mock/request :post %1 %2))]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes [["Test_Faction/Carrier/Sensor Array.stl" data]
                                                ["Test-Faction/Carrier/Sensor Array_Supported.stl" data]])))
      (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
      (is (= ["Downloads" nil :antenna]
             ((juxt :part/bundle :part/class :part/role-hint) (first (parts!)))))
      (is (= 200 (:status (post! "/imports/cancel" {}))))
      (doseq [[field value] [["bundle" "Test Faction"] ["class" "Carrier"] ["role" "Sensor Array"]]]
        (is (= 200 (:status (post! "/settings/classifications/add" {"field" field "value" value})))))
      (let [before (catalog/listing! cat)]
        (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
        (is (= 1 (count (parts!))))
        (is (= ["Test Faction" "Carrier" :sensor-array #{:unsupported :supported}]
               ((juxt :part/bundle :part/class :part/role-hint (comp set :part/variants)) (first (parts!)))))
        (let [id (:part/id (first (parts!)))]
          (is (= 200 (:status (post! "/imports/split" {"group" id}))))
          (is (= 2 (count (parts!))))
          (is (every? #(= :sensor-array (:part/role-hint %)) (parts!))))
        (is (= before (catalog/listing! cat)))
        (is (= 200 (:status (post! "/imports/cancel" {}))))
        (is (= before (catalog/listing! cat))))
      (testing "renames and deletion affect the next review"
        (is (= 200 (:status (post! "/settings/classifications/rename"
                                   {"field" "class" "value" "Carrier" "new-value" "Transport"}))))
        (is (= 200 (:status (post! "/settings/classifications/delete" {"field" "role" "value" "sensor-array"}))))
        (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
        (is (= [nil :antenna] ((juxt :part/class :part/role-hint) (first (parts!)))))
        (is (= 200 (:status (post! "/imports/cancel" {})))))
      (testing "unregistered effective labels from missing parts in another library are reused"
        (store/write! database #(d/transact! % [{:part/key [(random-uuid) "missing"] :part/id "missing"
                                                 :part/present? false :part/bundle-override "Test Faction"
                                                 :part/class-override "Carrier" :part/role-override :sensor-array}]))
        (is (not (contains? (:class (vocabulary/registered! database)) "Carrier")))
        (is (= 200 (:status (post! "/imports/start" {"archive" (str zip)}))))
        (is (= ["Test Faction" "Carrier" :sensor-array]
               ((juxt :part/bundle :part/class :part/role-hint) (first (parts!)))))
        (is (= 200 (:status (post! "/imports/commit" {}))))
        (is (= :sensor-array (:part/role-hint (catalog/summary! cat "Test Faction/Carrier/Sensor Array")))))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))

(deftest failed-move-restores-staging-and-catalog
  (let [started (fixture/start!) system (:system started) handler (:handler started)
        workspace (:shipyard.workspace/db system) lib (:shipyard.library/index system)
        cat (:shipyard.catalog/db system) directory (fs/create-temp-dir)
        zip (archives/archive! directory) session! #(importer/session! {:workspace workspace})
        post! #(handler (mock/request :post %1 %2)) before (catalog/listing! cat)
        obstruction (fs/file (index/root! lib) "New Fleet/Cruiser/Prow")]
    (try
      (testing "invalid archives keep the ordinary browser and catalog"
        (is (= 422 (:status (post! "/imports/start" {"archive" (str directory "/missing.zip")}))))
        (is (nil? (session!)))
        (is (= before (catalog/listing! cat))))
      (post! "/imports/start" {"archive" (str zip)})
      (fs/create-dirs (fs/parent obstruction))
      (spit obstruction "existing file")
      (testing "a failure after moving the first files restores their staged sources"
        (is (= 422 (:status (post! "/imports/commit" {}))))
        (is (= before (catalog/listing! cat)))
        (is (every? #(fs/regular-file? (:file %)) (vals @(:entries (session!)))))
        (is (not (fs/exists? (fs/path (index/root! lib) "New Fleet/Cruiser/Hull/unsupported.stl"))))
        (is (= "existing file" (slurp obstruction))))
      (testing "the same review succeeds after resolving the obstruction"
        (fs/delete obstruction)
        (is (= 200 (:status (post! "/imports/commit" {})))))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))

(deftest adding-a-variant-preserves-authored-metadata
  (let [started (fixture/start!) system (:system started) handler (:handler started)
        cat (:shipyard.catalog/db system) directory (fs/create-temp-dir)
        zip (fs/file directory "Synthetic Navy.zip") id (:hull fixture/ids)]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes [["Cruiser/hull_Supported.stl" (meshes/->binary-stl (meshes/cube))]])))
      (catalog/save-part-role! cat id :section)
      (catalog/save-part-orientation! cat id [0 0 1 0])
      (let [before (catalog/part-context! cat id)]
        (testing "a missing supported variant can be added without changing the existing model's authoring"
          (is (= 200 (:status (handler (mock/request :post "/imports/start" {"archive" (str zip)})))))
          (is (= 200 (:status (handler (mock/request :post "/imports/commit" {})))))
          (is (= (select-keys (:part before) [:part/role-hint :part/orientation :part/mounts :part/uid])
                 (select-keys (:part (catalog/part-context! cat id)) [:part/role-hint :part/orientation :part/mounts :part/uid])))))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))

(deftest archive-entry-paths-are-never-extraction-paths
  (let [directory (fs/create-temp-dir) output (fs/create-dirs (fs/path directory "output"))
        zip (fs/file directory "Untrusted.zip") data (meshes/->binary-stl (meshes/cube))]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes [["../escape.stl" data]
                                                ["/absolute.stl" data]
                                                ["__MACOSX/._Hull.stl" data]
                                                ["unfinished.zip.part" data]])))
      (testing "even absolute and traversal entry names produce only UUID files in staging"
        (let [{:keys [entries]} (archive/extract! zip output)]
          (is (= 2 (count entries)))
          (is (every? #(= output (fs/parent (:file %))) entries))
          (is (not (fs/exists? (fs/path directory "escape.stl"))))))
      (finally (fs/delete-tree directory)))))

(deftest empty-and-invalid-archives
  (let [started (fixture/start!) handler (:handler started)
        workspace (get-in started [:system :shipyard.workspace/db])
        cat (get-in started [:system :shipyard.catalog/db])
        before (catalog/listing! cat)
        directory (fs/create-temp-dir)
        output (fs/create-dirs (fs/path directory "output"))
        zip (fs/file directory "Empty.zip")
        invalid (archives/invalid-nested-archive! directory)
        post! #(handler (mock/request :post "/imports/start" {"archive" (str %)}))]
    (try
      (testing "a zero-byte selected archive is still an error, not a skipped nested file"
        (spit zip "")
        (let [response (post! zip)]
          (is (= 422 (:status response)))
          (is (.contains (:body response) "Cannot read ZIP Empty.zip"))))
      (testing "nested empty placeholders are reported even inside other nested ZIPs"
        (with-open [out (io/output-stream zip)]
          (.write out ^bytes (archives/zip-bytes [["Nested.zip" (archives/zip-bytes [["Empty(1).ZIP" (byte-array 0)]
                                                                                     ["Valid-empty.zip" (archives/zip-bytes [])]])]])))
        (is (= {:entries [] :skipped-empty-archives [["Empty.zip" "Nested.zip" "Empty(1).ZIP"]]}
               (archive/extract! zip output)))
        (is (empty? (fs/list-dir output)) "temporary nested ZIPs are removed")
        (let [response (post! zip)]
          (is (= 422 (:status response)))
          (is (.contains (:body response) "This archive contains no STL files."))))
      (testing "nonempty invalid nested ZIPs fail with their full source chain, without partial review"
        (let [response (post! invalid)]
          (is (= 422 (:status response)))
          (is (.contains (:body response) "Cannot read ZIP Broken Fleet.zip → Download.zip → broken.zip")))
        (is (nil? (importer/session! {:workspace workspace})))
        (is (= before (catalog/listing! cat)))
        (is (fs/regular-file? invalid)))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))

(deftest grouping-splitting-and-variant-assignment
  (let [started (fixture/start!) system (:system started) handler (:handler started)
        workspace (:shipyard.workspace/db system) lib (:shipyard.library/index system)
        cat (:shipyard.catalog/db system) directory (fs/create-temp-dir)
        zip (fs/file directory "Pairs.zip")
        post! #(handler (mock/request :post %1 %2))
        check! (fn [status url params]
                 (let [response (post! url params)]
                   (is (= status (:status response)) (:body response))))
        session! #(importer/session! {:workspace workspace})
        original (meshes/->binary-stl (meshes/cube 2))
        supported (meshes/->binary-stl (meshes/cube 3))
        before (catalog/listing! cat)]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes [["Cruiser/Hull.stl" original]
                                                ["Cruiser/Hull_Supported.stl" supported]])))
      (check! 200 "/imports/start" {"archive" (str zip)})
      (let [session (session!) entries @(:entries session)
            group (:group (first (vals entries)))
            supported-id (:key (first (filter #(= :supported (:variant %)) (vals entries))))
            parts! #(into {} (map (juxt :part/id identity)) (catalog/browse (catalog/listing! (:catalog session)) {}))
            ids! #(vec (keys (parts!)))]
        (is (= 1 (count (parts!))))
        (is (= #{:supported :unsupported} (set (:part/variants (get (parts!) group)))))
        (testing "variant filters validate input and reject stale scroll batches"
          (is (= 400 (:status (handler (mock/request :get "/orient/parts?variant=invalid")))))
          (is (= 200 (:status (handler (mock/request :get "/orient/parts?variant=supported")))))
          (is (= 204 (:status (handler (mock/request :get "/orient/parts?variant=unsupported&chunk=1&page=2")))))
          (handler (mock/request :get "/orient/parts?variant=")))
        (catalog/save-part-orientation! (:catalog session) group [0 1 0 0])
        (testing "changing a pair swaps assignments, changes the source and clears its old pose"
          (check! 200 "/imports/variant" {"file" supported-id "variant" "unsupported"})
          (is (= :unsupported (get-in @(:entries session) [supported-id :variant])))
          (is (= (:file (get entries supported-id)) (index/fresh-source-file! (:library session) group)))
          (is (nil? (:part/orientation (get (parts!) group))))
          (let [sources (store/read! (:store session)
                                     #(d/pull % '[{:part/sources [*]}]
                                              [:part/key [(:library @(:state (:catalog session))) group]]))
                source (first (filter #(= :unsupported (:source/variant %)) (:part/sources sources)))]
            (is (= (:file (get entries supported-id))
                   (fs/file (index/root! (:library session)) (:source/path source))))))
        (testing "failed regrouping rolls back the database and both in-memory projections"
          (let [snapshot (parts!) indexed @(:state (:library session)) files @(:entries session)
                transact d/transact!]
            (with-redefs [d/transact! (fn [conn tx]
                                        (let [result (transact conn tx)]
                                          (when (some #(and (vector? %) (= :part/name-override (nth % 2 nil))) tx)
                                            (throw (ex-info "Injected review failure" {})))
                                          result))]
              (check! 422 "/imports/split" {"group" group}))
            (is (= snapshot (parts!)))
            (is (= indexed @(:state (:library session))))
            (is (= files @(:entries session)))))
        (testing "splitting separates destinations and preserves all files"
          (check! 200 "/imports/split" {"group" group})
          (is (= 2 (count (parts!))))
          (is (= 2 (count (set (map :id (importer/plan! session))))))
          (is (= (set (keys entries)) (set (keys @(:entries session))))))
        (testing "manual grouping joins differently named rows with reviewed labels"
          (post! "/orient/selection" {"visible" (pr-str (ids!)) "selected" (ids!)})
          (check! 200 "/imports/group" {"name" "Corrected Hull"})
          (is (= "[]" (:bulk-selection (get-in @(:state workspace) [:workspaces :browse]))))
          (is (= 1 (count (parts!))))
          (is (= "Corrected Hull" (:part/name (first (vals (parts!))))))
          (is (= before (catalog/listing! cat))))
        (testing "invalid member and variant requests do not change the review"
          (let [snapshot @(:entries session)]
            (check! 422 "/imports/variant" {"file" "missing" "variant" "supported"})
            (check! 400 "/imports/variant" {"file" supported-id "variant" "invalid"})
            (is (= snapshot @(:entries session)))))
        (testing "publication uses the corrected file assignments"
          (check! 200 "/imports/commit" {})
          (doseq [[variant data] [["unsupported" supported] ["supported" original]]]
            (let [file (fs/path (index/root! lib) "Pairs/Cruiser/Corrected Hull" (str variant ".stl"))]
              (is (= (seq data) (seq (java.nio.file.Files/readAllBytes file))))))))
      (finally (fixture/stop! started) (fs/delete-tree directory)))))
