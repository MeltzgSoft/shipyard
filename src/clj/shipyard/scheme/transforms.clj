(ns shipyard.scheme.transforms
  "Pure scheme representation and explicit identity-based updates."
  (:require [shipyard.loadout.transforms :as loadout]))

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

(defn scheme? [value]
  (and (map? value)
       (every? #{:scheme/id :scheme/name :scheme/roles :scheme/instances} (keys value))
       (uuid? (:scheme/id value)) (loadout/name? (:scheme/name value))
       (map? (:scheme/roles value)) (<= (count (:scheme/roles value)) 256)
       (every? (fn [[role material]] (and (keyword? role) (material? material))) (:scheme/roles value))
       (or (not (contains? value :scheme/instances))
           (and (map? (:scheme/instances value)) (<= (count (:scheme/instances value)) 4097)
                (every? instance-entry? (:scheme/instances value))))))

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
