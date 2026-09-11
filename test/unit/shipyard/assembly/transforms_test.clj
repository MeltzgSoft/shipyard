(ns shipyard.assembly.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [shipyard.assembly.model-test :as fixture]
            [shipyard.assembly.transforms :as transforms]
            [shipyard.catalog.db :as catalog]
            [shipyard.geom :as geom]))

(def available #{"hull" "weapon" "turret"})

(defn apply-op [draft op]
  (transforms/transition fixture/database draft
                         (assoc op :revision (:revision draft)) available))

(deftest transition-test
  (testing "select, assign duplicate parts, nest, replace, clear and reset"
    (let [hull (:draft (apply-op transforms/empty-draft {:op :hull :part-id "hull"}))
          weapon (:draft (apply-op hull {:op :assign :slot [[:weapon 0]] :part-id "weapon"}))
          nested (:draft (apply-op weapon {:op :assign :slot [[:weapon 0] [:turret 0]] :part-id "turret"}))
          duplicate (:draft (apply-op nested {:op :assign :slot [[:weapon 1]] :part-id "weapon"}))
          replaced (:draft (apply-op duplicate {:op :assign :slot [[:weapon 0]] :part-id "weapon"}))
          cleared (:draft (apply-op replaced {:op :clear :slot [[:weapon 0]]}))]
      (is (= "hull" (:hull hull)))
      (is (= 3 (count (:assignments duplicate))))
      (is (= {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"} (:assignments replaced)))
      (is (= {[[:weapon 1]] "weapon"} (:assignments cleared)))
      (is (nil? (:hull (:draft (apply-op cleared {:op :reset})))))))
  (testing "invalid operations preserve the draft"
    (let [draft (:draft (apply-op transforms/empty-draft {:op :hull :part-id "hull"}))]
      (doseq [[op error] [[{:op :assign :slot [[:gone 0]] :part-id "weapon"} :stale-slot]
                          [{:op :assign :slot [[:weapon 0]] :part-id "turret"} :incompatible-role]
                          [{:op :hull :part-id "missing"} :missing-part]]]
        (let [result (apply-op draft op)]
          (is (= error (:error result)))
          (is (= draft (:draft result)))))
      (is (= :stale-revision (:error (transforms/transition fixture/database draft
                                                            {:op :reset :revision 0} available))))
      (is (= :unavailable-mesh (:error (transforms/transition fixture/database draft
                                                              {:op :assign :slot [[:weapon 0]] :part-id "weapon" :revision 1}
                                                              #{"hull"})))))))

(deftest placements-test
  (testing "empty draft and independent repeated source placements"
    (is (= {} (transforms/placements fixture/database transforms/empty-draft)))
    (let [draft {:hull "hull" :assignments {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"
                                            [[:weapon 0] [:turret 0]] "turret"}}
          result (transforms/placements fixture/database draft)]
      (is (= 4 (count result)))
      (is (= [-2.0 0.0 0.0] (geom/transform-point (get-in result [[[:weapon 0]] :matrix]) [0 0 0])))
      (is (= [2.0 0.0 0.0] (geom/transform-point (get-in result [[[:weapon 1]] :matrix]) [0 0 0])))))
  (testing "missing root has no renderable scene"
    (is (= {} (transforms/placements fixture/database {:hull "gone" :assignments {}})))))

(deftest plug-to-socket-placement-test
  (let [hull-plug (assoc fixture/plug :mount/id :prow :mount/pos [3.0 2.0 1.0])
        prow-socket (assoc fixture/socket :mount/id :hull :mount/accepts [:hull]
                           :mount/pos [1.0 0.0 0.0])
        hull (assoc fixture/hull :part/mounts [hull-plug])
        prow (assoc fixture/weapon :part/id "prow" :part/role-hint :prow :part/mounts [prow-socket])
        database (d/db-with (d/empty-db catalog/schema) [hull prow])
        placement (get-in (transforms/placements database {:hull "hull"
                                                           :assignments {[[:prow 0]] "prow"}})
                          [[[:prow 0]] :matrix])]
    (testing "the selected prow socket lands on the hull plug"
      (is (= [3.0 2.0 1.0]
             (geom/transform-point placement (:mount/pos prow-socket)))))))

(deftest commands-test
  (testing "removals precede ready sets and cold replacements remove old geometry"
    (let [before {[] {:part-id "hull" :matrix geom/identity-matrix}
                  [[:weapon 0]] {:part-id "old" :matrix geom/identity-matrix}}
          after (assoc before [[:weapon 0]] {:part-id "new" :matrix geom/identity-matrix})
          commands (transforms/commands before after {"hull" "abc"} false)]
      (is (= {:op :remove :slot [[:weapon 0]]} (first commands)))
      (is (= :set (:op (second commands))))
      (is (= "hull" (:part-id (second commands))))
      (is (= [{:op :reset}] (transforms/commands before {} {} true))))))
