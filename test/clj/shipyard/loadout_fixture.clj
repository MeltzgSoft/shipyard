(ns shipyard.loadout-fixture
  (:require [shipyard.assembly-fixture :as fixture]))

(def assignments
  (into {} (map (fn [[path role]] [path (fixture/ids role)]))
        [[[[:prow 0]] :prow] [[[:bridge 0]] :bridge]
         [[[:antenna 0]] :antenna] [[[:antenna 1]] :antenna]
         [[[:weapon 0]] :weapon] [[[:weapon 1]] :weapon]
         [[[:mirrored-weapon 0]] :weapon] [[[:mirrored-weapon 1]] :weapon]
         [[[:weapon 0] [:turret 0]] :turret] [[[:weapon 1] [:turret 0]] :turret]
         [[[:mirrored-weapon 0] [:turret 0]] :turret]
         [[[:mirrored-weapon 1] [:turret 0]] :turret]]))

(def draft {:revision 1 :hull (:hull fixture/ids) :assignments assignments})

(defn deps [started]
  (let [sys (:system started)]
    {:assembly (:shipyard.assembly/db sys) :preview (:shipyard.loadout.operations/preview sys)
     :loadouts (:shipyard.loadout/db sys) :library (:shipyard.library/index sys)
     :catalog (:shipyard.catalog/db sys) :jobs (:shipyard.http/jobs sys)
     :cache (:shipyard.mesh/cache sys)}))
