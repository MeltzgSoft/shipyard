(ns shipyard.scheme.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.scheme.transforms :as t]))

(def empty-store {:version 1 :schemes {}})

(def material {:base [0.2 0.3 0.4] :metalness 0.5 :roughness 0.6 :paint "Blue"})
(def record {:scheme/id #uuid "93c3df7f-4b02-44cd-a4ed-c239f6851a8c" :scheme/name "Navy"
             :scheme/layers {"Primary" material}})

(deftest put-record-test
  (testing "explicit identity and operation"
    (let [store (:store (t/put-record empty-store record :create))]
      (is (= record (get-in store [:schemes (:scheme/id record)])))
      (is (= :id-exists (:error (t/put-record store record :create))))
      (is (= :missing-scheme (:error (t/put-record empty-store record :update))))
      (is (= :invalid-operation (:error (t/put-record store record :delete))))
      (is (= :invalid-scheme (:error (t/put-record store {} :create))))
      (is (= "Updated" (get-in (t/put-record store (assoc record :scheme/name "Updated") :update)
                               [:scheme :scheme/name]))))))
