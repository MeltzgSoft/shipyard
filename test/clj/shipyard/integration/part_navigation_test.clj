(ns shipyard.integration.part-navigation-test
  (:require [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.part-navigation-fixture :as parts]
            [shipyard.workspace.db :as workspace]))

(deftest navigation-uses-the-whole-filtered-cohort-and-retains-table-context
  (let [started (fixture/start! false parts/build! (fn [_])) sys (:system started) h (:handler started)
        state (:shipyard.workspace/db sys)
        get! #(h (assoc (mock/request :get %1 %2) :headers {"hx-request" "true"}))]
    (try
      (get! "/orient/parts" {"q" "Filtered" "page" "1" "table-scroll" "321"})
      (let [response (get! "/workspace/browse" {"part-id" (parts/id 49)})
            browse (workspace/workspace! state :browse)]
        (is (= 200 (:status response)))
        (is (= 65 (count (:part-order browse))))
        (is (.contains ^String (:body response) "Filtered%2050")))
      (get! "/workspace/browse" {"part-id" (parts/id 50)})
      (is (= (parts/id 50) (:selection (workspace/workspace! state :browse))))
      (is (= {"q" "Filtered" "page" "1" "table-scroll" "321"} (:filters (workspace/workspace! state :browse))))
      (let [context (workspace/active-context! state)
            response (h (assoc (mock/request :post "/parts/metadata/individual" {"part-id" (parts/id 49)})
                               :headers {"x-shipyard-workspace" "browse" "x-shipyard-activation" (str (dec (:activation context)))}))]
        (is (= 204 (:status response)))
        (is (= (parts/id 50) (:selection (workspace/workspace! state :browse)))))
      (get! "/workspace/browse" {"table" "1"})
      (get! "/orient/parts" {"q" "Unrelated"})
      (get! "/workspace/browse" {"part-id" "Fleet/Cruiser/Unrelated"})
      (is (= ["Fleet/Cruiser/Unrelated"] (:part-order (workspace/workspace! state :browse))))
      (get! "/workspace/browse" {"table" "1"})
      (get! "/orient/parts" {"q" "" "variant" "all"})
      (get! "/workspace/browse" {"part-id" "Fleet/Cruiser/Support Only"})
      (let [response (h (mock/request :post "/parts/metadata/individual"
                                      {"part-id" "Fleet/Cruiser/Support Only" "name" "Support Only saved"
                                       "bundle" "Fleet" "class" "Cruiser" "role" "prow"}))]
        (is (= 200 (:status response)))
        (is (.contains ^String (:body response) "part-id=Fleet/Cruiser/Unrelated"))
        (is (.contains ^String (:body response) "Filtered%2064")))
      (finally (fixture/stop! started)))))
