(ns shipyard.integration.variant-preview-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.part-browser.variants :as variants]
            [shipyard.part-variants-fixture :as source]
            [shipyard.workspace.db :as workspace]))

(deftest variant-selection-validates-the-current-source-and-never-becomes-an-editable-part
  (let [started (fixture/start! false source/build! (fn [_])) sys (:system started) h (:handler started)
        cat (:shipyard.catalog/db sys) ws (:shipyard.workspace/db sys)
        entry (first (filter #(= source/b (:owner %)) (variants/files! cat)))
        before (catalog/listing! cat) id (str "library-file-" (:key entry))
        request #(h (mock/request :get (str "/workspace/browse?variant-file=" %)
                                  nil))
        fragment #(h (assoc-in (mock/request :get "/workspace/browse?resume=1") [:headers "hx-request"] "true"))]
    (try
      (is (= 400 (:status (h (mock/request :get "/workspace/browse?variant-file=a&variant-file=b")))))
      (request (:key entry))
      (is (= :variant (:view (workspace/workspace! ws :browse))))
      (is (= (:key entry) (:variant-selection (workspace/workspace! ws :browse))))
      (let [deadline (+ (System/nanoTime) (* 20 1000000000))
            ready (loop []
                    (let [response (fragment)]
                      (if (or (get-in response [:headers "HX-Trigger-After-Swap"])
                              (> (System/nanoTime) deadline))
                        response
                        (do (Thread/sleep 20) (recur)))))]
        (is (some? (get-in ready [:headers "HX-Trigger-After-Swap"])))
        (is (.contains ^String (:body ready) (:path entry))))
      (is (nil? (catalog/part (catalog/listing! cat) id)))
      (is (= 422 (:status (h (mock/request :post "/parts/metadata/row"
                                           {"part-id" id "name" "Changed" "bundle" "Fleet" "class" "Cruiser" "role" "weapon"})))))
      (is (= before (catalog/listing! cat)))
      (spit (fs/file (:root started) (:path entry)) "externally changed")
      (is (.contains ^String (:body (fragment)) "changed"))
      (is (nil? (get-in (fragment) [:headers "HX-Trigger-After-Swap"])))
      (request "missing")
      (is (.contains ^String (:body (fragment)) "no longer in the library"))
      (is (= before (catalog/listing! cat)))
      (finally (fixture/stop! started)))))
