(ns shipyard.paint.transforms
  "Pure editor targets, input parsing and scheme mutations."
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.loadout.model :as loadout]
            [shipyard.scheme.material :as material]
            [shipyard.scheme.transforms :as scheme]))

(defn targets [database draft]
  (let [instances (mapv (fn [[path id]]
                          (let [part (catalog/part database id)]
                            {:key (pr-str path) :path path :part-id id :role (:part/role-hint part)
                             :label (str (or (:part/name part) id) " · " (if (empty? path) "Hull" (pr-str path)))}))
                        (loadout/part-tree draft))]
    (into instances
          (for [role (sort (set (keep :role instances)))]
            {:key (str "role/" (name role)) :role role :label (str "Role default · " (name role))}))))

(defn target-material [record target]
  (if (contains? target :path)
    (material/resolve-material record (:path target) (:part-id target) (:role target))
    (or (get-in record [:scheme/roles (:role target)]) material/neutral)))

(defn affected-paths [record targets target]
  (if (contains? target :path)
    [(:path target)]
    (mapv :path (filter #(and (contains? % :path) (= (:role %) (:role target))
                              (not= (:part-id %) (get-in record [:scheme/instances (:path %) :part-id]))) targets))))

(defn color-hex [base]
  (apply str "#" (map #(format "%02x" (Math/round (* 255.0 %))) base)))

(defn parse-material [{:strs [base metalness roughness paint]}]
  (try
    (when (and (string? base) (re-matches #"#[0-9a-fA-F]{6}" base))
      (let [value {:base (mapv #(/ (Integer/parseInt (subs base % (+ % 2)) 16) 255.0) [1 3 5])
                   :metalness (Double/parseDouble metalness) :roughness (Double/parseDouble roughness)
                   :paint (or paint "")}]
        (when (scheme/material? value) value)))
    (catch Exception _ nil)))

(defn edit-record [record target value clear?]
  (cond
    (nil? target) {:error :missing-target}
    (and clear? (not (contains? target :path))) {:error :invalid-target}
    (and (not clear?) (not (scheme/material? value))) {:error :invalid-material}
    clear? {:scheme (update record :scheme/instances #(dissoc (or % {}) (:path target)))}
    (contains? target :path) {:scheme (assoc-in record [:scheme/instances (:path target)]
                                                {:part-id (:part-id target) :material value})}
    :else {:scheme (assoc-in record [:scheme/roles (:role target)] value)}))
