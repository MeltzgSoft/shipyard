(ns shipyard.store.transforms
  "Pure conversion between normalized storage entities and domain values."
  (:require [clojure.set :as set]
            [shipyard.regions.model :as regions]
            [shipyard.regions.registry :as registry]))

(def material-keys
  {:base :material/base :metalness :material/metalness
   :roughness :material/roughness :paint :material/paint})

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
                              [(:layer/id layer) {:name (:layer/name layer)
                                                  :preview-name (:layer/preview-name layer)}]))) layers)
   :deleted (into #{} (comp (filter :layer/deleted?) (map :layer/id)) layers)})

(defn layer-tx [library id {:keys [name preview-name]}]
  (cond-> {:layer/key [library id] :layer/id id :layer/library [:library/id library]
           :layer/name name :layer/preview-name preview-name
           :layer/builtin? (boolean (some #{id} regions/builtins))
           :layer/deleted? false}
    name (assoc :layer/active-name [library name])))

(defn layers-tx [library shared]
  (mapv (fn [[id definition]] (layer-tx library id definition)) (registry/definitions shared)))

(defn part-value [entity shared]
  (let [mounts (mapv #(dissoc % :db/id :mount/uid :mount/mirror :mount/order) (sort-by :mount/order (:part/mounts entity)))]
    (cond-> (-> entity
                (dissoc :db/id :part/library :part/key :part/imported? :part/regions
                        :part/sources :migration/extra)
                (assoc :part/mounts mounts))
      (:part/role-override entity) (assoc :part/role-hint (:part/role-override entity)
                                          :part/role-source :manual)
      (:part/regions entity) (assoc :part/paint-regions (region (:part/regions entity) shared)))))

(defn loadout-value [entity]
  (cond-> {:loadout/id (:loadout/id entity) :loadout/name (:loadout/name entity)
           :loadout/hull (get-in entity [:loadout/hull :part/id])
           :loadout/slots (into {} (map (fn [slot] [(:slot/path slot) (get-in slot [:slot/part :part/id])]))
                                (:loadout/slots entity))}
    (:loadout/scheme entity) (assoc :loadout/scheme (get-in entity [:loadout/scheme :scheme/id]))))

(defn scheme-value [entity]
  (let [targets (into {} (map (juxt :db/id identity)) (:scheme/targets entity))
        value {:scheme/id (:scheme/id entity) :scheme/name (:scheme/name entity)
               :scheme/roles (into {} (map (juxt :binding/role material)) (:scheme/roles entity))
               :scheme/layers (into {} (map (fn [binding] [(get-in binding [:binding/layer :layer/id]) (material binding)]))
                                    (:scheme/layers entity))
               :scheme/layer-ids? true
               :scheme/instances (into {} (keep (fn [target]
                                                  (when-let [m (material target)]
                                                    [(:target/path target) {:part-id (get-in target [:target/part :part/id])
                                                                            :material m}]))) (:scheme/targets entity))
               :scheme/details (into {} (keep (fn [target]
                                                (when-let [detail (:target/details target)]
                                                  [(:target/path target)
                                                   {:part-id (get-in target [:target/part :part/id])
                                                    :mesh-key (get-in detail [:detail/content :mesh/sha])
                                                    :faces (faces (:detail/chunks detail))}]))) (:scheme/targets entity))
               :scheme/groups (mapv (fn [group]
                                      (cond-> (select-keys group [:group/id :group/name :group/order])
                                        true (assoc :group/members
                                                    (mapv (fn [member]
                                                            (let [target (get targets (get-in member [:membership/target :db/id]))]
                                                              {:path (:target/path target)
                                                               :part-id (get-in target [:target/part :part/id])}))
                                                          (sort-by :membership/order (:group/members group))))
                                        (material group) (assoc :group/material (material group))))
                                    (sort-by :group/order (:scheme/groups entity)))}]
    (select-keys value (conj (set (:scheme/fields entity)) :scheme/id :scheme/name :scheme/roles))))

(defn scheme-tx
  "parts maps current path to stable part lookup ref; layer refs are library scoped."
  [library parts record]
  (let [id (:scheme/id record)
        target-key (fn [path part] (str "target:" id ":" (pr-str [path part])))
        identities (set (concat
                         (map (fn [[path v]] [path (:part-id v)]) (:scheme/instances record))
                         (map (fn [[path v]] [path (:part-id v)]) (:scheme/details record))
                         (map (juxt :path :part-id) (mapcat :group/members (:scheme/groups record)))))
        targets (mapv (fn [[path part]]
                        (let [instance (get-in record [:scheme/instances path])
                              detail (get-in record [:scheme/details path])]
                          (cond-> {:db/id (target-key path part) :target/path path :target/part (get parts part)}
                            (= part (:part-id instance)) (merge (material-tx (:material instance)))
                            (= part (:part-id detail)) (assoc :target/details
                                                              {:detail/content [:mesh/sha (:mesh-key detail)]
                                                               :detail/chunks (chunks (:faces detail))}))))
                      (sort-by pr-str identities))]
    {:scheme/id id :scheme/name (:scheme/name record) :scheme/deleted? false
     :scheme/fields (set (keys record))
     :scheme/roles (mapv (fn [[role m]] (assoc (material-tx m) :binding/role role)) (:scheme/roles record))
     :scheme/layers (mapv (fn [[layer m]] (assoc (material-tx m) :binding/layer [:layer/key [library layer]])) (:scheme/layers record))
     :scheme/targets targets
     :scheme/groups (mapv (fn [group]
                            (merge (select-keys group [:group/id :group/name :group/order])
                                   (material-tx (:group/material group))
                                   {:group/members (mapv (fn [order member]
                                                           {:membership/order order
                                                            :membership/target (target-key (:path member) (:part-id member))})
                                                         (range) (:group/members group))}))
                          (:scheme/groups record))}))
