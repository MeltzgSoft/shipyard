(ns shipyard.regions.registry
  "Shared layer entities. Identity is independent of labels and part usage."
  (:require [shipyard.regions.model :as model]))

(def empty-registry {:version 1 :revision 0 :layers {} :deleted #{}})

(defn id? [value]
  (model/detail-id? value))

(defn valid? [value]
  (and (map? value) (= #{:version :revision :layers :deleted} (set (keys value)))
       (= 1 (:version value)) (nat-int? (:revision value))
       (map? (:layers value)) (every? id? (keys (:layers value)))
       (every? #(and (= #{:name :preview-name} (set (keys %)))
                     (model/name? (:name %)) (model/name? (:preview-name %))) (vals (:layers value)))
       (set? (:deleted value)) (every? id? (:deleted value))
       (not-any? (:deleted value) (keys (:layers value)))))

(defn definitions [registry]
  (merge (into {} (map (fn [id] [id {:name id :preview-name id}]) model/builtins)) (:layers registry)))

(defn ids [registry]
  (into model/builtins (sort-by #(vector (get-in registry [:layers % :name]) %) (keys (:layers registry)))))

(defn discover [registry regions]
  (reduce (fn [result region]
            (update result :layers
                    #(merge (apply dissoc (:layer-definitions region) (:deleted result)) %)))
          registry regions))

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
                   (update :revision inc))}))
