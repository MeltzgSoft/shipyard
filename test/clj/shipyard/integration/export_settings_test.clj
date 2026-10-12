(ns shipyard.integration.export-settings-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.library.index :as index]
            [shipyard.settings.db :as settings]
            [shipyard.settings.export-directory :as directory]
            [shipyard.store.db :as store]
            [shipyard.workspace.db :as workspace]))

(deftest export-settings-http-preserves-library-and-workspace
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        db (:shipyard.store/db sys) library (:shipyard.library/index sys)
        ws (:shipyard.workspace/db sys) destination (str (fs/path (:temp started) "exports"))
        before (index/parts! library) browser (workspace/workspace! ws :browse)]
    (try
      (is (nil? (settings/export-root! db)))
      (handler (mock/request :get "/workspace/settings"))
      (is (= 200 (:status (handler (mock/request :post "/settings/export-root" {"export-root" destination})))))
      (is (= destination (settings/export-root! db)))
      (is (fs/directory? destination))
      (spit (fs/file destination "existing.zip") "existing bundle")
      (doseq [bad ["" (str (:root started)) (str (fs/path (:root started) "nested-export")) (str (fs/path destination "existing.zip"))]]
        (is (= 422 (:status (handler (mock/request :post "/settings/export-root" {"export-root" bad})))))
        (is (= destination (settings/export-root! db))))
      (testing "an unwritable directory preserves saved settings and bundles"
        (when (not (str/starts-with? (System/getProperty "os.name") "Windows"))
          (let [denied (fs/create-dir (fs/path (:temp started) "denied"))]
            (try
              (fs/set-posix-file-permissions denied "r-xr-xr-x")
              (is (= 422 (:status (handler (mock/request :post "/settings/export-root" {"export-root" (str denied)})))))
              (is (= destination (settings/export-root! db)))
              (finally (fs/set-posix-file-permissions denied "rwxrwxrwx"))))))
      (is (= "existing bundle" (slurp (fs/file destination "existing.zip"))))
      (is (not (fs/exists? (fs/path (:root started) "nested-export"))))
      (is (= 200 (:status (handler (mock/request :post "/settings/variants" {"variant" "supported" "direction" "up"})))))
      (is (= [:supported :unsupported :unsupported-pitted] (settings/variant-preference! db)))
      (handler (mock/request :post "/settings/variants" {"variant" "supported" "direction" "up"}))
      (is (= [:supported :unsupported :unsupported-pitted] (settings/variant-preference! db)))
      (is (= before (index/parts! library)))
      (is (= browser (workspace/workspace! ws :browse)))
      (finally (fixture/stop! started)))))

(deftest export-settings-survive-reopening-and-work-without-library
  (let [temp (fs/create-temp-dir) database (fs/path temp "database") root (str (fs/path temp "export"))]
    (try
      (let [db (store/open! database)]
        (try
          (settings/save-export-root! db (directory/prepare! root nil))
          (settings/save-variant-preference! db [:supported :unsupported])
          (is (thrown? Exception (settings/save-variant-preference! db [:supported :supported])))
          (finally (store/close! db))))
      (let [db (store/open! database)]
        (try
          (is (= root (settings/export-root! db)))
          (is (= [:supported :unsupported] (settings/variant-preference! db)))
          (finally (store/close! db))))
      (testing "resolved aliases into a library are rejected before creation"
        (when-not (= "Windows" (first (str/split (System/getProperty "os.name") #" ")))
          (let [library (fs/create-dir (fs/path temp "library")) alias (fs/path temp "alias")]
            (fs/create-sym-link alias library)
            (is (thrown-with-msg? Exception #"outside the library" (directory/prepare! (str (fs/path alias "new")) (str library))))
            (is (not (fs/exists? (fs/path library "new")))))))
      (finally (fs/delete-tree temp)))))
