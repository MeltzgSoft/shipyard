(ns shipyard.paint.groups
  "Pure group membership and ordering operations."
  (:require [shipyard.loadout.transforms :as loadout]
            [shipyard.scheme.transforms :as scheme]))

(defn ordered [record]
  (vec (sort-by :group/order (:scheme/groups record))))

(defn normalize [groups]
  (mapv #(assoc %2 :group/order %1) (range) groups))

(defn members [targets keys]
  (let [keys (cond (nil? keys) [] (string? keys) [keys] :else keys)
        instances (into {} (for [target targets :when (contains? target :path)]
                             [(:key target) (select-keys target [:path :part-id])]))]
    (when (and (sequential? keys) (every? instances keys))
      (vec (distinct (map instances keys))))))

(defn change [record operation id name selected direction]
  (let [groups (ordered record) existing (first (filter #(= id (:group/id %)) groups))
        index (first (keep-indexed #(when (= id (:group/id %2)) %1) groups))]
    (cond
      (nil? record) {:error "Choose a scheme first."}
      (and (not= operation :create) (nil? existing)) {:error "That group is no longer available."}
      (and (#{:create :rename} operation) (not (loadout/name? name))) {:error "Enter a group name between 1 and 200 characters."}
      (and (#{:create :members} operation) (or (nil? selected) (not-every? scheme/member? selected)))
      {:error "Choose instances from the current model."}
      (and (= operation :create) (empty? selected)) {:error "Check at least one instance to group."}
      (and (= operation :create) (some #(= id (:group/id %)) groups)) {:error "That group already exists."}
      (and (= operation :order) (not (#{"up" "down"} direction))) {:error "Choose Move up or Move down."}
      :else
      (let [replacement (case operation
                          :create (conj groups {:group/id id :group/name name :group/order (count groups) :group/members selected})
                          :rename (mapv #(if (= id (:group/id %)) (assoc % :group/name name) %) groups)
                          :members (mapv #(if (= id (:group/id %)) (assoc % :group/members selected) %) groups)
                          :delete (filterv #(not= id (:group/id %)) groups)
                          :order (let [destination (+ index (if (= direction "up") -1 1))]
                                   (if (<= 0 destination (dec (count groups)))
                                     (assoc groups index (nth groups destination) destination existing) groups))
                          nil)]
        (if replacement {:scheme (assoc record :scheme/groups (normalize replacement))}
            {:error "Unknown group operation."})))))
