(ns shipyard.integration.standalone-variants-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.standalone-variants-fixture :as parts]
            [shipyard.variant-availability-fixture :as variants]
            [shipyard.workspace.db :as workspace]))

(defn- rows [response]
  (mapv second (re-seq #"data-part-row=\"([^\"]+)\"" (:body response))))

(deftest default-browsing-includes-standalone-variants-with-one-row-per-group
  (let [started (fixture/start! false #(parts/build! % 60) (fn [_]))
        handler (:handler started) ws (:shipyard.workspace/db (:system started))
        get! #(handler (mock/request :get "/orient/parts" %))
        expected (set (concat [variants/all variants/plain variants/cut variants/supported parts/pitted parts/supported-pitted]
                              (map variants/extra-id (range 60))))]
    (try
      (let [first-page (rows (get! {}))
            next-page (rows (get! {:page "2" :chunk "1"}))
            combined (concat first-page next-page)]
        (is (= 50 (count first-page)))
        (is (= 16 (count next-page)))
        (is (= expected (set combined)))
        (is (= 66 (count combined)))
        (is (= 1 (count (filter #{variants/all} combined)))))
      (handler (mock/request :post "/orient/select-all" {:selection "all"}))
      (is (= expected (set (edn/read-string (:bulk-selection (workspace/workspace! ws :browse))))))
      (is (= #{variants/supported variants/all parts/supported-pitted} (set (rows (get! {:variant "supported"})))))
      (is (= #{variants/all variants/cut parts/pitted parts/supported-pitted}
             (set (rows (get! {:variant "unsupported-pitted"})))))
      (is (= #{variants/supported parts/pitted parts/supported-pitted}
             (set (rows (get! {:has-unsupported "missing" :variant "all"})))))
      (is (= [variants/supported] (rows (get! {:q "Supported Only"}))))
      (finally (fixture/stop! started)))))
