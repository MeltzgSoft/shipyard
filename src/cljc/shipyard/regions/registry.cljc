(ns shipyard.regions.registry
  "Shared layer entities. Identity is independent of labels and part usage."
  (:require [shipyard.regions.model :as model]
            [shipyard.regions.colors :as colors]))

(def empty-registry {:version 1 :revision 0 :layers {} :deleted #{}})

(defn id? [value]
  (model/detail-id? value))

(defn definitions [registry]
  (merge (into {} (map (fn [id] [id {:name id :preview-name id}]) model/builtins)) (:layers registry)))

(defn ids [registry]
  (into model/builtins (sort-by #(vector (get-in registry [:layers % :name]) %) (keys (:layers registry)))))

(defn assign-colors
  "Assign missing colors once, across the entire library rather than per part."
  [registry]
  (reduce (fn [result id]
            (assoc-in result [:layers id :preview-color]
                      (colors/choose (keep :preview-color (vals (:layers result))))))
          registry (sort-by #(vector (get-in registry [:layers % :preview-name]) %)
                            (remove #(get-in registry [:layers % :preview-color]) (keys (:layers registry))))))

(defn discover [registry regions]
  (assign-colors
   (reduce (fn [result region]
             (update result :layers
                     #(merge (apply dissoc (:layer-definitions region) (:deleted result)) %)))
           registry regions)))

(defn change [registry revision action id name new-id]
  (cond
    (not= revision (:revision registry)) {:error "Layers changed. Reopen this part before retrying."}
    (not (#{"add" "rename" "delete"} action)) {:error "Unknown layer operation."}
    (and (not= action "add") (not (contains? (:layers registry) id)))
    {:error "Choose an existing detail layer. Primary and Secondary cannot be renamed or deleted."}
    (and (#{"add" "rename"} action)
         (or (not (model/name? name))
             (some (fn [[other entry]] (and (not= other id) (= name (:name entry)))) (definitions registry))))
    {:error "Enter a unique layer name between 1 and 200 characters."}
    (and (= action "add") (or (not (id? new-id)) (contains? (:layers registry) new-id) (contains? (:deleted registry) new-id)))
    {:error "Invalid layer identity."}
    :else
    {:selected (if (= action "add") new-id id)
     :registry (-> (case action
                     "add" (assoc-in registry [:layers new-id] {:name name :preview-name name})
                     "rename" (assoc-in registry [:layers id :name] name)
                     "delete" (-> registry (update :layers dissoc id) (update :deleted conj id)))
                   (assign-colors)
                   (update :revision inc))}))
