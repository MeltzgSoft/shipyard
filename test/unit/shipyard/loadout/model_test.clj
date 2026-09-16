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
    (testing "empty mounts are valid, including hull-only and unfinished nested assemblies"
      (doseq [assignments [{} {[[:weapon 0]] "weapon"}
                           {[[:weapon 0]] "weapon" [[:weapon 0] [:turret 0]] "turret"}]]
        (let [partial-draft (assoc draft :assignments assignments)
              result (m/validate assembly/database partial-draft available)]
          (is (nil? (:error result)))
          (is (= partial-draft (:draft result)))
          (is (= (inc (count assignments)) (count (:scene result)))))))
    (is (= :unavailable-mesh (:error (m/validate assembly/database draft #{"hull"}))))
    (is (= :stale-slot (:error (m/validate assembly/database (assoc-in draft [:assignments [[:gone 0]]] "turret") available))))))

(deftest empty-mount-count-test
  (let [record {:loadout/hull "hull" :loadout/slots {}}
        count-empty #(m/empty-mount-count assembly/database (assoc record :loadout/slots %))]
    (is (= 2 (count-empty {})))
    (is (= 2 (count-empty {[[:weapon 0]] "weapon"})))
    (is (= 1 (count-empty {[[:weapon 0]] "weapon" [[:weapon 0] [:turret 0]] "turret"})))
    (is (= 2 (count-empty {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"})))
    (is (zero? (count-empty {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"
                             [[:weapon 0] [:turret 0]] "turret" [[:weapon 1] [:turret 0]] "turret"})))
    (is (nil? (count-empty {[[:gone 0]] "weapon"})))
    (is (nil? (m/empty-mount-count assembly/database (assoc record :loadout/hull "gone"))))))

(deftest part-tree-test
  (is (= [] (m/part-tree {})))
  (is (= [[[] "hull"]] (m/part-tree {:hull "hull"})))
  (let [assignments {[[:weapon 10]] "weapon"
                     [[:weapon 2] [:turret 0]] "turret"
                     [[:bridge 0]] "bridge"
                     [[:weapon 2]] "weapon"}]
    (is (= [[[] "hull"]
            [[[:bridge 0]] "bridge"]
            [[[:weapon 2]] "weapon"]
            [[[:weapon 2] [:turret 0]] "turret"]
            [[[:weapon 10]] "weapon"]]
           (m/part-tree {:hull "hull" :assignments assignments})))))
