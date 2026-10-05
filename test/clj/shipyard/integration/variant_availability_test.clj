(ns shipyard.integration.variant-availability-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.variant-availability-fixture :as parts]
            [shipyard.workspace.db :as workspace]))

(defn- rows [response]
  (mapv second (re-seq #"data-part-row=\"([^\"]+)\"" (:body response))))

(deftest availability-filters-share-pagination-selection-and-navigation
  (let [started (fixture/start! false #(parts/build! % 60) (fn [_]))
        h (:handler started) state (:shipyard.workspace/db (:system started))
        get! #(h (assoc (mock/request :get %1 %2) :headers {"hx-request" "true"}))
        filters {"has-unsupported" "available" "has-supported" "missing" "has-pitted" "missing"}
        matching (vec (cons parts/plain (map parts/extra-id (range 60))))]
    (try
      (testing "combined availability applies to every batch and select-all"
        (is (= (subvec matching 0 50) (rows (get! "/orient/parts" filters))))
        (is (= (subvec matching 50) (rows (get! "/orient/parts" (assoc filters "page" "2" "chunk" "1")))))
        (is (= 200 (:status (h (mock/request :post "/orient/select-all" (assoc filters "selection" "all"))))))
        (is (= (set matching) (set (edn/read-string (:bulk-selection (workspace/workspace! state :browse)))))))
      (testing "individual navigation includes unloaded matches and Back restores filters"
        (get! "/workspace/browse" {"part-id" (parts/extra-id 59)})
        (is (= matching (:part-order (workspace/workspace! state :browse))))
        (get! "/workspace/browse" {"table" "1"})
        (is (= filters (select-keys (:filters (workspace/workspace! state :browse)) (keys filters)))))
      (testing "a new filter rejects stale chunks and exposes supported-only rows"
        (let [changed {"has-unsupported" "missing" "has-supported" "available" "has-pitted" ""}]
          (is (= [parts/supported] (rows (get! "/orient/parts" changed))))
          (is (= 204 (:status (get! "/orient/parts" (assoc filters "page" "2" "chunk" "1")))))
          (is (= changed (select-keys (:filters (workspace/workspace! state :browse)) (keys changed))))))
      (testing "existing Variant and search filters intersect with availability"
        (is (empty? (rows (get! "/orient/parts" {"has-unsupported" "missing" "variant" "unsupported"}))))
        (is (= [parts/all] (rows (get! "/orient/parts" {"has-unsupported" "available" "has-supported" "available"
                                                        "has-pitted" "available" "variant" "all" "q" "All"}))))
        (is (empty? (rows (get! "/orient/parts" {"q" "No match"})))))
      (testing "transport rejects invalid availability choices"
        (doseq [field ["has-unsupported" "has-supported" "has-pitted"]]
          (is (= 400 (:status (get! "/orient/parts" {field "invalid"}))))))
      (finally (fixture/stop! started)))))
