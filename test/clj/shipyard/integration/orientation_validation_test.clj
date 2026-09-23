(ns shipyard.integration.orientation-validation-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.catalog.db :as catalog]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.part.orientation :as orientation]))

(deftest bulk-validation-precedes-all-durable-writes
  (let [started (fixture/start!) c (:shipyard.catalog/db (:system started))
        a (:prow fixture/ids) b (:bridge fixture/ids) root (str (:root started))
        q45 (orientation/from-euler-degrees 45 0 0)]
    (try
      (doseq [id [a b]] (catalog/save-part-orientation! c id q45))
      (let [before (mapv #(slurp (sidecar/sidecar-file root %)) [a b])]
        (doseq [invalid [[0 0 0 0] [Double/NaN 0 0 1] [Double/POSITIVE_INFINITY 0 0 1] [1 2 3] ["bad" 0 0 1]]]
          (let [result ((:handler started) (mock/request :post "/orient/save"
                                                         {"orientations" (pr-str {a [0 0 0 1] b invalid})}))]
            (is (= 422 (:status result)))
            (is (= before (mapv #(slurp (sidecar/sidecar-file root %)) [a b])))
            (doseq [id [a b]]
              (is (saves/same-pose? q45 (:part/orientation (catalog/part (catalog/snapshot! c) id)))))))
        (is (thrown? clojure.lang.ExceptionInfo (catalog/save-part-orientation! c a [0 0 0 0])))
        (is (= before (mapv #(slurp (sidecar/sidecar-file root %)) [a b]))))
      (let [q [1.0e308 0.0 0.0 1.0e308]
            result ((:handler started) (mock/request :post "/orient/save" {"orientations" (pr-str {a q})}))]
        (is (= 200 (:status result)))
        (is (saves/same-pose? q (:part/orientation (persisted/authored! c a)))))
      (finally (fixture/stop! started)))))
