(ns shipyard.integration.importer-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [shipyard.fixtures :as meshes]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.importer.archive :as archive]
            [shipyard.library.index :as index]))

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
        (is (= 200 (:status (post! "/imports/selection" {"selection" "all"}))))
        (is (= 3 (count (edn/read-string (get-in @(:state workspace) [:workspaces :browse :bulk-selection])))))
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
        (let [entries (archive/extract! zip output)]
          (is (= 2 (count entries)))
          (is (every? #(= output (fs/parent (:file %))) entries))
          (is (not (fs/exists? (fs/path directory "escape.stl"))))))
      (finally (fs/delete-tree directory)))))
