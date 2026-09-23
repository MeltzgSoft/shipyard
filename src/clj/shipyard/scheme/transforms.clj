(ns shipyard.scheme.transforms
  "Pure scheme representation and explicit identity-based updates."
  (:require [shipyard.loadout.transforms :as loadout]
            [shipyard.paint.faces :as faces]
            [shipyard.regions.model :as regions]))

(def empty-store {:version 1 :schemes {}})

(defn unit-number? [value]
  (and (number? value) (Double/isFinite (double value)) (<= 0 value 1)))

(defn material? [value]
  (and (map? value)
       (every? #{:base :metalness :roughness :paint} (keys value))
       (vector? (:base value)) (= 3 (count (:base value)))
       (every? unit-number? (:base value))
       (unit-number? (:metalness value)) (unit-number? (:roughness value))
       (or (not (contains? value :paint))
           (and (string? (:paint value)) (<= (count (:paint value)) 200)))))

(defn instance-entry? [[path value]]
  (and (or (= [] path) (loadout/slot-path? path))
       (map? value) (= #{:part-id :material} (set (keys value)))
       (loadout/part-id? (:part-id value)) (material? (:material value))))

(defn member? [value]
  (and (map? value) (= #{:path :part-id} (set (keys value)))
       (or (= [] (:path value)) (loadout/slot-path? (:path value)))
       (loadout/part-id? (:part-id value))))

(defn group? [value]
  (and (map? value)
       (every? #{:group/id :group/name :group/order :group/members :group/material} (keys value))
       (uuid? (:group/id value)) (loadout/name? (:group/name value))
       (nat-int? (:group/order value))
       (vector? (:group/members value)) (every? member? (:group/members value))
       (= (count (:group/members value)) (count (set (:group/members value))))
       (or (not (contains? value :group/material)) (material? (:group/material value)))))

(defn groups? [value]
  (and (vector? value) (every? group? value)
       (= (count value) (count (set (map :group/id value))))
       (= (count value) (count (set (map :group/order value))))))

(defn scheme? [value]
  (and (map? value)
       (every? #{:scheme/id :scheme/name :scheme/roles :scheme/instances :scheme/groups :scheme/details :scheme/layers :scheme/layer-ids?} (keys value))
       (or (not (contains? value :scheme/layer-ids?)) (true? (:scheme/layer-ids? value)))
       (or (not (contains? value :scheme/groups)) (groups? (:scheme/groups value)))
       (or (not (contains? value :scheme/layers))
           (and (map? (:scheme/layers value))
                (or (not (:scheme/layer-ids? value))
                    (every? #(or (some #{%} regions/builtins) (regions/detail-id? %)) (keys (:scheme/layers value))))
                (every? (fn [[name value]] (and (regions/name? name) (material? value))) (:scheme/layers value))))
       (uuid? (:scheme/id value)) (loadout/name? (:scheme/name value))
       (map? (:scheme/roles value)) (<= (count (:scheme/roles value)) 256)
       (every? (fn [[role material]] (and (keyword? role) (material? material))) (:scheme/roles value))
       (or (not (contains? value :scheme/instances))
           (and (map? (:scheme/instances value)) (<= (count (:scheme/instances value)) 4097)
                (every? instance-entry? (:scheme/instances value))))
       (or (not (contains? value :scheme/details))
           (and (map? (:scheme/details value))
                (<= (count (:scheme/details value)) 4097)
                (every? (fn [[path layer]]
                          (and (or (= [] path) (loadout/slot-path? path)) (faces/layer? layer)))
                        (:scheme/details value))))))

(defn store? [value]
  (and (map? value) (= #{:version :schemes} (set (keys value)))
       (= 1 (:version value)) (map? (:schemes value))
       (every? (fn [[id record]] (and (= id (:scheme/id record)) (scheme? record))) (:schemes value))))

(defn put-record [store record mode]
  (cond
    (not (scheme? record)) {:error :invalid-scheme}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= mode :create) (contains? (:schemes store) (:scheme/id record))) {:error :id-exists}
    (and (= mode :update) (not (contains? (:schemes store) (:scheme/id record)))) {:error :missing-scheme}
    :else {:store (assoc-in store [:schemes (:scheme/id record)] record) :scheme record}))

(defn delete-record [store id]
  (if (contains? (:schemes store) id)
    {:store (update store :schemes dissoc id)}
    {:error :missing-scheme}))
