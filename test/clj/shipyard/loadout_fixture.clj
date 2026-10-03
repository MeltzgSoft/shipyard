(ns shipyard.loadout-fixture
  (:require [babashka.fs :as fs]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout.db :as loadouts]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.strokes :as strokes]
            [shipyard.wire :as wire]))

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
  "Source folders whose roles are inferred by the real scanner."
  [root]
  (fixture/library! root)
  (doseq [role [:hull :prow :bridge :antenna :weapon :turret]]
    (let [old-id (fixture/ids role) new-id (scanned-ids role)]
      (when (not= old-id new-id)
        (fs/create-dirs (fs/parent (fs/path root new-id)))
        (fs/move (fs/path root old-id) (fs/path root new-id)))))
  root)

(defn author-scanned! [cat]
  (doseq [[role id] scanned-ids :when (not= role :hint)]
    (catalog/save-authoring! cat id
                             (cond-> (get (fixture/authored) (fixture/ids role))
                               (#{:hull :prow :bridge :antenna :weapon :turret} role) (dissoc :part-role)))))

(defn save-class! [sys]
  (let [state (:state (:shipyard.assembly/db sys))
        draft (:draft @state)
        id (or (:loadout-id draft) (random-uuid))
        draft (assoc draft :loadout-id id :name "Fixture class")]
    (loadouts/put! (:shipyard.loadout/db sys) {:loadout/id id :loadout/name "Fixture class" :loadout/hull (:hull draft) :loadout/slots (:assignments draft)} :create)
    (swap! state assoc :draft draft)
    id))

(defn detail-layer! [sys part-id material]
  (let [library (:shipyard.library/index sys) cache (:shipyard.mesh/cache sys)
        key (:mesh-key (cache/ensure! cache (index/fresh-source-file! library part-id)))
        mesh (wire/decode (java.nio.file.Files/readAllBytes (fs/path (cache/tier-file cache key 0))))]
    (index/record-mesh-key! library part-id key (quot (count (:indices mesh)) 3))
    {:part-id part-id :mesh-key key :faces (zipmap (strokes/mesh-face-keys mesh) (repeat material))}))
