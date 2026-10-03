(ns shipyard.store.transforms
  "Pure conversion between normalized storage entities and domain values."
  (:require [clojure.set :as set]
            [shipyard.regions.model :as regions]
            [shipyard.regions.registry :as registry]))

(def material-keys
  {:base :material/base :metalness :material/metalness
   :roughness :material/roughness :glow :material/glow :paint :material/paint})

(defn material-tx [material] (set/rename-keys material material-keys))
(defn material [entity]
  (when (:material/base entity)
    (set/rename-keys (select-keys entity (vals material-keys)) (set/map-invert material-keys))))

(defn chunks [faces]
  (mapv (fn [index entries]
          {:chunk/index index :chunk/version 1 :chunk/payload (vec entries)})
        (range) (partition-all 4096 (sort-by key faces))))

(defn faces [chunks]
  (into {} (mapcat :chunk/payload) (sort-by :chunk/index chunks)))

(defn region-tx [library value]
  {:region/content [:mesh/sha (:mesh-key value)]
   :region/revision (:revision value)
   :region/masks
   (mapv (fn [[layer entries]]
           {:mask/layer [:layer/key [library layer]]
            :mask/chunks (chunks (into {} (map (fn [[face _]] [face true])) entries))})
         (sort-by key (group-by val (remove #(= "Primary" (val %)) (:faces value)))))})

(defn region [entity shared]
  (when entity
    (let [assignments (into {} (mapcat (fn [mask] (map (fn [face] [face (get-in mask [:mask/layer :layer/id])])
                                                       (keys (faces (:mask/chunks mask)))))) (:region/masks entity))
          used (into regions/builtins (sort (remove (set regions/builtins) (set (vals assignments)))))]
      {:version 2 :mesh-key (get-in entity [:region/content :mesh/sha])
       :revision (:region/revision entity) :layers used :faces assignments
       :layer-definitions (select-keys (:layers shared) used)})))

(defn layer-registry [revision layers]
  {:version 1 :revision (or revision 0)
   :layers (into {} (keep (fn [layer]
                            (when-not (or (:layer/deleted? layer) (:layer/builtin? layer))
                              [(:layer/id layer) (cond-> {:name (:layer/name layer)
                                                          :preview-name (:layer/preview-name layer)}
                                                   (:layer/preview-color layer) (assoc :preview-color (:layer/preview-color layer)))]))) layers)
   :deleted (into #{} (comp (filter :layer/deleted?) (map :layer/id)) layers)})

(defn layer-tx [library id {:keys [name preview-name preview-color]}]
  (cond-> {:layer/key [library id] :layer/id id :layer/library [:library/id library]
           :layer/name name :layer/preview-name preview-name
           :layer/builtin? (boolean (some #{id} regions/builtins))
           :layer/deleted? false}
    name (assoc :layer/active-name [library name])
    preview-color (assoc :layer/preview-color preview-color)))

(defn layers-tx [library shared]
  (mapv (fn [[id definition]] (layer-tx library id definition)) (registry/definitions shared)))

(defn part-value [entity shared]
  (let [mounts (mapv #(dissoc % :db/id :mount/uid :mount/mirror :mount/order) (sort-by :mount/order (:part/mounts entity)))]
    (cond-> (-> entity
                (dissoc :db/id :part/library :part/key :part/regions
                        :part/sources)
                (assoc :part/mounts mounts))
      (:part/name-override entity) (assoc :part/name (:part/name-override entity))
      (:part/bundle-override entity) (assoc :part/bundle (:part/bundle-override entity))
      (:part/class-override entity) (assoc :part/class (:part/class-override entity))
      (:part/role-override entity) (assoc :part/role-hint (:part/role-override entity)
                                          :part/role-source :manual)
      (:part/regions entity) (assoc :part/paint-regions (region (:part/regions entity) shared)))))

(defn loadout-value [entity]
  (cond-> {:loadout/id (:loadout/id entity) :loadout/name (:loadout/name entity)
           :loadout/hull (get-in entity [:loadout/hull :part/id])
           :loadout/slots (into {} (map (fn [slot] [(:slot/path slot) (get-in slot [:slot/part :part/id])]))
                                (:loadout/slots entity))}
    (contains? entity :loadout/allow-other-factions?)
    (assoc :loadout/allow-other-factions? (:loadout/allow-other-factions? entity))))

(defn scheme-value [entity]
  {:scheme/id (:scheme/id entity) :scheme/name (:scheme/name entity)
   :scheme/layers (into {} (map (fn [binding] [(get-in binding [:binding/layer :layer/id]) (material binding)]))
                        (:scheme/layers entity))})

(defn scheme-tx [library record]
  {:scheme/id (:scheme/id record) :scheme/name (:scheme/name record) :scheme/deleted? false
   :scheme/layers (mapv (fn [[layer m]] (assoc (material-tx m) :binding/layer [:layer/key [library layer]]))
                        (:scheme/layers record))})

(defn paint-value [entity]
  (select-keys
   {:paint/details (into {} (keep (fn [target]
                                    (when-let [detail (:target/details target)]
                                      [(:target/path target)
                                       {:part-id (get-in target [:target/part :part/id])
                                        :mesh-key (get-in detail [:detail/content :mesh/sha])
                                        :faces (faces (:detail/chunks detail))}]))) (:paint/targets entity))}
   (set (:paint/fields entity))))

(defn paint-tx [parts ship-id record]
  {:paint/fields (set (keys record))
   :paint/targets (mapv (fn [[path detail]]
                          {:db/id (str "target:ship:" ship-id ":" (pr-str [path (:part-id detail)]))
                           :target/path path :target/part (get parts (:part-id detail))
                           :target/details {:detail/content [:mesh/sha (:mesh-key detail)]
                                            :detail/chunks (chunks (:faces detail))}})
                        (sort-by (comp pr-str key) (:paint/details record)))})

(defn ship-value [entity]
  (cond-> {:ship/id (:ship/id entity) :ship/name (:ship/name entity)
           :ship/class (get-in entity [:ship/class :loadout/id])
           :ship/paint (paint-value (:ship/paint entity))}
    (:ship/scheme entity) (assoc :ship/scheme (get-in entity [:ship/scheme :scheme/id]))))
