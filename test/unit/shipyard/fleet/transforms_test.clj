(ns shipyard.fleet.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fleet.transforms :as t]))

(def record {:fleet/id (random-uuid) :fleet/name "Fleet" :fleet/library (random-uuid)
             :fleet/entries {} :fleet/extras {} :fleet/variant-preference [:supported]})

(deftest put-record-test
  (testing "create and update use identity rather than list name"
    (is (= {:fleet record} (t/put-record false record :create)))
    (is (= {:fleet record} (t/put-record true record :update)))
    (is (= :id-exists (:error (t/put-record true record :create))))
    (is (= :missing-fleet (:error (t/put-record false record :update)))))
  (testing "invalid mode and domain input remain recoverable"
    (is (= :invalid-operation (:error (t/put-record true record :other))))
    (is (= :invalid-fleet (:error (t/put-record true (assoc record :fleet/name " ") :update))))))

(deftest delete-record-test
  (testing "deletion requires a live UUID identity"
    (is (= {:deleted (:fleet/id record)} (t/delete-record true (:fleet/id record))))
    (is (= :missing-fleet (:error (t/delete-record false (:fleet/id record)))))
    (is (= :invalid-fleet-id (:error (t/delete-record true "Fleet"))))))

(deftest source-path-test
  (testing "Windows inventory paths become portable archive identities"
    (is (= "Fleet/Cruiser/Hull/unsupported.stl"
           (t/source-path "Fleet\\Cruiser\\Hull\\unsupported.stl" \\))))
  (testing "Unix backslashes remain literal names for path validation to reject"
    (is (= "Fleet\\Hull/unsupported.stl"
           (t/source-path "Fleet\\Hull/unsupported.stl" \/)))))
