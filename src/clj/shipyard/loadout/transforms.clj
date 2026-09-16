(ns shipyard.loadout.transforms
  "Pure durable representation. Catalog compatibility belongs to operations."
  (:require [clojure.string :as str]))

(def empty-store {:version 1 :loadouts {}})

(defn name? [value]
  (and (string? value) (<= 1 (count value) 200) (not (str/blank? value))))

(defn part-id? [value]
  (and (string? value) (<= 1 (count value) 2048)))

(defn slot-path? [path]
  (and (vector? path) (<= 1 (count path) 16)
       (every? #(and (vector? %) (= 2 (count %))
                     (keyword? (first %)) (integer? (second %))
                     (<= 0 (second %) 255)) path)))

(defn loadout? [record]
  (and (map? record)
       (every? #{:loadout/id :loadout/name :loadout/hull :loadout/slots :loadout/scheme} (keys record))
       (uuid? (:loadout/id record)) (name? (:loadout/name record))
       (part-id? (:loadout/hull record))
       (map? (:loadout/slots record)) (<= (count (:loadout/slots record)) 4096)
       (every? (fn [[path id]] (and (slot-path? path) (part-id? id))) (:loadout/slots record))
       (or (not (contains? record :loadout/scheme)) (uuid? (:loadout/scheme record)))))

(defn store? [value]
  (and (map? value) (= #{:version :loadouts} (set (keys value)))
       (= 1 (:version value)) (map? (:loadouts value))
       (every? (fn [[id record]] (and (= id (:loadout/id record)) (loadout? record)))
               (:loadouts value))))

(defn put-record
  "Create and replace are explicit; names never select the record to update."
  [store record mode]
  (cond
    (not (loadout? record)) {:error :invalid-loadout}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= :create mode) (contains? (:loadouts store) (:loadout/id record))) {:error :id-exists}
    (and (= :update mode) (not (contains? (:loadouts store) (:loadout/id record)))) {:error :missing-loadout}
    :else {:store (assoc-in store [:loadouts (:loadout/id record)] record) :loadout record}))
