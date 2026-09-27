(ns shipyard.paint.transforms
  "Pure editor targets, input parsing and scheme mutations."
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.loadout.model :as loadout]
            [shipyard.scheme.material :as material]
            [shipyard.scheme.transforms :as scheme]))

(defn targets
  ([database draft] (targets database draft nil))
  ([database draft record]
   (let [instances (mapv (fn [[path id]]
                           (let [part (catalog/part database id)]
                             {:key (pr-str path) :path path :part-id id :role (:part/role-hint part)
                              :layers (:layers (catalog/part-regions part))
                              :name (or (:part/name part) id)
                              :label (str (or (:part/name part) id) " · " (if (empty? path) "Hull" (pr-str path)))}))
                         (loadout/part-tree draft))]
     (into (into (into instances (for [layer (when (or record (seq instances)) (catalog/region-layers database))]
                                   {:key (str "layer/" layer) :layer-id layer :label (get-in (catalog/region-registry database) [:layers layer :name] layer)})) (map (fn [g] {:key (str "group/" (:group/id g)) :group-id (:group/id g) :label (:group/name g)})
                                                                                                                                                                     (sort-by :group/order (:scheme/groups record))))
           (for [role (when-not (:paint/ship-id record) (sort (set (keep :role instances))))]
             {:key (str "role/" (name role)) :role role :label (str "Role default · " (name role))})))))

(defn target-material [profile target]
  (let [record (material/effective-profile profile)]
    (cond
      (:layer-id target) (or (get-in record [:scheme/layers (:layer-id target)]) (get-in record [:scheme/layers "Primary"]) material/neutral)
      (:group-id target) (or (:group/material (first (filter #(= (:group-id target) (:group/id %)) (:scheme/groups record)))) material/neutral)
      (contains? target :path) (material/resolve-material record (:path target) (:part-id target) (:role target))
      :else (or (get-in record [:scheme/roles (:role target)]) material/neutral))))

(defn affected-paths [record targets target]
  (let [source (cond (contains? target :path) [:instance (:path target)]
                     (:group-id target) [:group (:group-id target)]
                     :else [:role (:role target)])
        preview (if (:group-id target)
                  (update record :scheme/groups (fn [groups] (mapv #(if (= (:group-id target) (:group/id %))
                                                                      (assoc % :group/material material/neutral) %) groups))) record)]
    (cond
      (:layer-id target) (mapv :path (filter #(and (contains? % :path) (#{:role :layer} (first (material/material-source record (:path %) (:part-id %) (:role %))))) targets))
      (contains? target :path) [(:path target)]
      :else
      (mapv :path (filter #(and (contains? % :path)
                                (= source (material/material-source preview (:path %) (:part-id %) (:role %)))) targets)))))

(defn color-hex [base]
  (apply str "#" (map #(format "%02x" (Math/round (* 255.0 %))) base)))

(defn parse-material [{:strs [base metalness roughness glow paint] :as params}]
  (try
    (when (and (string? base) (re-matches #"#[0-9a-fA-F]{6}" base))
      (let [value (cond-> {:base (mapv #(/ (Integer/parseInt (subs base % (+ % 2)) 16) 255.0) [1 3 5])
                           :metalness (Double/parseDouble metalness) :roughness (Double/parseDouble roughness)
                           :paint (or paint "")}
                    (contains? params "glow") (assoc :glow (Double/parseDouble glow)))]
        (when (scheme/material? value) value)))
    (catch Exception _ nil)))

(defn edit-record [record target value clear?]
  (cond
    (nil? target) {:error :missing-target}
    (and clear? (not (or (contains? target :path) (:layer-id target) (:group-id target)))) {:error :invalid-target}
    (and (not clear?) (not (scheme/material? value))) {:error :invalid-material}
    (and clear? (:layer-id target)) {:scheme (update record :scheme/layers dissoc (:layer-id target))}
    (and clear? (:group-id target)) {:scheme (update record :scheme/groups #(mapv (fn [group] (if (= (:group-id target) (:group/id group)) (dissoc group :group/material) group)) %))}
    (:layer-id target) {:scheme (-> record (assoc :scheme/layer-ids? true) (assoc-in [:scheme/layers (:layer-id target)] value))}
    (:group-id target) {:scheme (update record :scheme/groups
                                        (fn [groups] (mapv #(if (= (:group-id target) (:group/id %)) (assoc % :group/material value) %) groups)))}
    clear? {:scheme (update record :scheme/instances #(dissoc (or % {}) (:path target)))}
    (contains? target :path) {:scheme (assoc-in record [:scheme/instances (:path target)]
                                                {:part-id (:part-id target) :material value})}
    :else {:scheme (assoc-in record [:scheme/roles (:role target)] value)}))

(defn parse-detail
  "Accept old colour-only clients; a supplied finish must be complete and valid."
  [{:strs [color] :as params}]
  (if (some #(contains? params %) ["metalness" "roughness" "glow"])
    (some-> (parse-material (assoc params "base" color))
            (select-keys [:base :metalness :roughness :glow]))
    (:base (parse-material {"base" color "metalness" "0" "roughness" "1"}))))
