(ns shipyard.loadout-fixture
  (:require [babashka.fs :as fs]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.sidecar :as sidecar]))

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

(def scanned-ids
  (assoc fixture/ids
         :weapon "Synthetic Navy/Cruiser/weapons/weapon"
         :turret "Synthetic Navy/Cruiser/weapons/turrets/turret"))

(def scanned-assignments
  (into {} (map (fn [[path id]]
                  [path (condp = id
                          (:weapon fixture/ids) (:weapon scanned-ids)
                          (:turret fixture/ids) (:turret scanned-ids)
                          id)])) assignments))

(defn scanned-library!
  "Real scanned roles with authored mounts, matching an imported authored library."
  [root]
  (fixture/library! root)
  (doseq [role [:hull :prow :bridge :antenna :weapon :turret]]
    (let [old-id (fixture/ids role) new-id (scanned-ids role)]
      (sidecar/update-sidecar! (str root) old-id dissoc :part/role)
      (when (not= old-id new-id)
        (fs/create-dirs (fs/parent (fs/path root new-id)))
        (fs/move (fs/path root old-id) (fs/path root new-id)))))
  root)
