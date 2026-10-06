(ns shipyard.loadout.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.loadout.transforms :as t]))

(def record {:loadout/id #uuid "00000000-0000-0000-0000-000000000001"
             :loadout/name "Cruiser" :loadout/hull "navy/hull"
             :loadout/slots {[[:weapon 0]] "navy/weapon" [[:weapon 1]] "navy/weapon"
                             [[:weapon 0] [:turret 0]] "navy/turret"}})

(deftest put-record-test
  (testing "explicit identity and operation; only the supplied record is returned"
    (is (= {:loadout record} (t/put-record false record :create)))
    (is (= {:loadout record} (t/put-record true record :update)))
    (is (= {:error :id-exists} (t/put-record true record :create)))
    (is (= {:error :missing-loadout} (t/put-record false record :update)))
    (is (= {:error :invalid-operation} (t/put-record true record :delete)))
    (is (= {:error :invalid-loadout} (t/put-record true {} :update)))))

(deftest delete-record-test
  (testing "invalid and absent identities retain their distinct errors"
    (let [id (:loadout/id record)]
      (is (= {:deleted id} (t/delete-record true id)))
      (is (= {:error :missing-loadout} (t/delete-record false id)))
      (is (= {:error :invalid-loadout-id} (t/delete-record false (str id)))))))
