(ns shipyard.loadout.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [shipyard.loadout.model :as m]
            [shipyard.assembly.model-test :as assembly]
            [shipyard.loadout.transforms-test :refer [record]]))

(deftest from-record-test
  (let [edit (m/from-record record 4 :edit) copy (m/from-record record 5 :duplicate)]
    (is (= (:loadout/id record) (:loadout-id edit)))
    (is (= (:loadout/name record) (:name edit)))
    (is (nil? (:loadout-id copy)))
    (is (= "Cruiser - Copy" (:name copy)))
    (is (= (:loadout/slots record) (:assignments copy)))
    (is (= (:loadout/scheme record) (:scheme copy)))))

(deftest to-record-test
  (is (= record (m/to-record (m/from-record record 2 :edit) (:loadout/id record) "Cruiser")))
  (is (nil? (m/to-record {} (:loadout/id record) " "))))

(deftest validate-test
  (let [draft {:hull "hull" :assignments {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"
                                          [[:weapon 0] [:turret 0]] "turret" [[:weapon 1] [:turret 0]] "turret"}}
        available #{"hull" "weapon" "turret"}]
    (is (= 5 (count (:scene (m/validate assembly/database draft available)))))
    (testing "Save accepts the same role sources as Assemble"
      (doseq [source [:manual :inferred :class]]
        (let [database (d/db-with assembly/database
                                  (mapv #(hash-map :part/id % :part/role-source source) available))]
          (is (= 5 (count (:scene (m/validate database draft available)))))
          (is (= :incompatible-role
                 (:error (m/validate (d/db-with database [{:part/id "weapon" :part/role-hint :bridge}])
                                     draft available))))
          (is (= :unauthored-hull
                 (:error (m/validate (d/db-with database [{:part/id "hull" :part/role-hint :weapon}])
                                     draft available)))))))
    (is (= :no-draft (:error (m/validate assembly/database {} available))))
    (is (= :incomplete-loadout (:error (m/validate assembly/database (assoc draft :assignments {}) available))))
    (is (= :unavailable-mesh (:error (m/validate assembly/database draft #{"hull"}))))
    (is (= :stale-slot (:error (m/validate assembly/database (assoc-in draft [:assignments [[:gone 0]]] "turret") available))))))
