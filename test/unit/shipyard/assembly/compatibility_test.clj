(ns shipyard.assembly.compatibility-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly.model :as model]
            [shipyard.assembly.model-test :as fixture]
            [shipyard.assembly.transforms :as transforms]
            [shipyard.loadout.model :as loadouts]
            [shipyard.store.catalog-fixture :as d]))

(deftest faction-and-class-permissions-are-independent
  (let [foreign (assoc fixture/weapon :part/bundle "other")
        universal (assoc fixture/weapon :part/class "Universal")
        foreign-universal (assoc universal :part/bundle "other")
        check #(model/candidate-error fixture/hull :hull fixture/socket ["hull"] %1 %2)
        enabled {:allow-other-factions? true}]
    (is (= :different-bundle (check foreign {})))
    (is (nil? (check foreign enabled)))
    (is (nil? (check universal {})))
    (is (= :different-bundle (check foreign-universal {})))
    (is (nil? (check foreign-universal enabled)))
    (is (= :different-class (check (assoc foreign :part/class :escort) enabled)))
    (is (= :different-class (check (dissoc universal :part/class) enabled)))
    (is (nil? (model/candidate-error (dissoc fixture/hull :part/class) :hull fixture/socket [] universal {})))
    (is (= :different-class (model/candidate-error (assoc fixture/hull :part/class "Universal")
                                                   :hull fixture/socket [] fixture/weapon enabled)))
    (testing "class and faction exceptions do not relax mount/source/cycle checks"
      (doseq [[part code] [[(assoc foreign-universal :part/role-hint :bridge) :incompatible-role]
                           [(assoc foreign-universal :part/renderable false) :unavailable-mesh]
                           [(assoc foreign-universal :part/mounts []) :plug-count]
                           [(assoc foreign-universal :part/mounts [(assoc fixture/plug :mount/axis [0 0 0])]) :invalid-plug]]]
        (is (= code (check part enabled))))
      (is (= :cycle (model/candidate-error fixture/hull :hull fixture/socket ["weapon"] foreign-universal enabled))))))

(deftest toggle-validates-the-whole-nested-tree-and-preserves-rejected-drafts
  (let [database (d/db-with fixture/database [{:part/id "weapon" :part/bundle "other" :part/class "Universal"}])
        draft {:revision 1 :hull "hull" :assignments {}}
        transition #(transforms/transition database %1 (assoc %2 :revision (:revision %1)) #{"hull" "weapon" "turret"})
        enabled (:draft (transition draft {:op :compatibility :allow-other-factions? true}))
        assigned (:draft (transition enabled {:op :assign :slot [[:weapon 0]] :part-id "weapon"}))
        nested (:draft (transition assigned {:op :assign :slot [[:weapon 0] [:turret 0]] :part-id "turret"}))
        rejected (transition nested {:op :compatibility :allow-other-factions? false})]
    (is (= :different-bundle (:error (transition draft {:op :assign :slot [[:weapon 0]] :part-id "weapon"}))))
    (is (= ["weapon"] (mapv :part/id (model/candidates database fixture/hull :hull fixture/socket ["hull"] enabled))))
    (is (empty? (model/candidates database fixture/hull :hull fixture/socket ["hull"] draft)))
    (is (= 3 (count (transforms/placements database nested))))
    (is (nil? (:error (loadouts/validate database nested #{"hull" "weapon" "turret"}))))
    (is (= :other-factions-in-use (:error rejected)))
    (is (= 422 (:status rejected)))
    (is (= nested (:draft rejected)))
    (is (= :stale-revision (:error (transforms/transition database nested {:op :compatibility :revision 0 :allow-other-factions? false} #{}))))
    (is (= :invalid-compatibility (:error (transition enabled {:op :compatibility :allow-other-factions? "true"}))))
    (let [cleared (:draft (transition nested {:op :clear :slot [[:weapon 0]]}))
          disabled (:draft (transition cleared {:op :compatibility :allow-other-factions? false}))]
      (is (false? (:allow-other-factions? disabled)))
      (is (= {} (:assignments disabled))))))

(deftest faction-permission-survives-record-transfers-with-legacy-defaults
  (let [id (random-uuid) draft {:hull "hull" :assignments {} :allow-other-factions? true}
        record (loadouts/to-record draft id "Mixed")]
    (is (true? (:loadout/allow-other-factions? record)))
    (doseq [mode [:edit :duplicate :preview]]
      (is (true? (:allow-other-factions? (loadouts/from-record record 1 mode)))))
    (let [legacy (dissoc record :loadout/allow-other-factions?)
          draft (loadouts/from-record legacy 1 :edit)]
      (is (not (:allow-other-factions? draft)))
      (is (false? (loadouts/unsaved? draft {id legacy})))
      (is (false? (loadouts/unsaved? draft {id (assoc legacy :loadout/allow-other-factions? false)})))
      (is (false? (loadouts/unsaved? (assoc draft :allow-other-factions? false) {id legacy})))
      (is (true? (loadouts/unsaved? (assoc draft :allow-other-factions? true) {id legacy}))))))
