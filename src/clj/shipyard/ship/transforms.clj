(ns shipyard.ship.transforms
  "Named vessels reference reusable ship classes and own their custom paint."
  (:require [shipyard.loadout.identity :as identity]
            [shipyard.paint.job :as job]))

(defn valid? [record]
  (and (map? record)
       (every? #{:ship/id :ship/name :ship/class :ship/scheme :ship/paint} (keys record))
       (uuid? (:ship/id record)) (identity/name? (:ship/name record))
       (uuid? (:ship/class record))
       (or (not (contains? record :ship/scheme)) (uuid? (:ship/scheme record)))
       (job/valid? (:ship/paint record))))

(defn put-record [store record mode]
  (cond
    (not (valid? record)) {:error :invalid-ship}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= mode :create) (contains? (:ships store) (:ship/id record))) {:error :id-exists}
    (and (= mode :update) (not (contains? (:ships store) (:ship/id record)))) {:error :missing-ship}
    :else {:store (assoc-in store [:ships (:ship/id record)] record) :ship record}))

(defn delete-record [store id]
  (if (contains? (:ships store) id)
    {:store (update store :ships dissoc id) :deleted id}
    {:error :missing-ship}))
