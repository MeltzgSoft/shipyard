(ns shipyard.loadout.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.loadout.transforms :as t]))

(def record {:loadout/id #uuid "00000000-0000-0000-0000-000000000001"
             :loadout/name "Cruiser" :loadout/hull "navy/hull"
             :loadout/slots {[[:weapon 0]] "navy/weapon" [[:weapon 1]] "navy/weapon"
                             [[:weapon 0] [:turret 0]] "navy/turret"}
             :loadout/scheme #uuid "00000000-0000-0000-0000-000000000002"})

(deftest representation-validation
  (is (t/loadout? record))
  (is (t/store? t/empty-store))
  (doseq [bad [(assoc record :loadout/name " ") (assoc record :loadout/id "uuid")
               (assoc record :workspace :assemble) (assoc record :loadout/scheme nil)
               (assoc record :loadout/slots {[] "part"})
               (assoc record :loadout/slots {[[:a 256]] "part"})]]
    (is (not (t/loadout? bad))))
  (is (not (t/store? {:version 2 :loadouts {}})))
  (is (not (t/store? {:version 1 :loadouts {(:loadout/scheme record) record}}))))

(deftest explicit-identity
  (let [created (t/put-record t/empty-store record :create)
        store (:store created)]
    (is (t/store? store))
    (is (= :id-exists (:error (t/put-record store record :create))))
    (is (= :missing-loadout (:error (t/put-record t/empty-store record :update))))
    (testing "names do not select update targets"
      (let [copy (assoc record :loadout/id (:loadout/scheme record))
            both (:store (t/put-record store copy :create))]
        (is (= 2 (count (:loadouts both))))
        (is (= copy (get-in (:store (t/put-record both (assoc record :loadout/name "Renamed") :update))
                            [:loadouts (:loadout/id copy)])))))))
