(ns shipyard.scheme.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.scheme.transforms :as t]))

(def material {:base [0.2 0.3 0.4] :metalness 0.5 :roughness 0.6 :paint "Blue"})
(def record {:scheme/id #uuid "93c3df7f-4b02-44cd-a4ed-c239f6851a8c" :scheme/name "Navy"
             :scheme/layers {"Primary" material}})

(deftest put-record-test
  (testing "explicit identity and validation without a replacement store"
    (is (= {:scheme record} (t/put-record false record :create)))
    (is (= {:scheme record} (t/put-record true record :update)))
    (is (= {:error :id-exists} (t/put-record true record :create)))
    (is (= {:error :missing-scheme} (t/put-record false record :update)))
    (is (= {:error :invalid-operation} (t/put-record true record :delete)))
    (is (= {:error :invalid-scheme} (t/put-record true {} :create)))))

(deftest delete-record-test
  (testing "preserve scheme deletion's empty success result and missing error"
    (is (= {} (t/delete-record true (:scheme/id record))))
    (is (= {:error :missing-scheme} (t/delete-record false (:scheme/id record))))))
