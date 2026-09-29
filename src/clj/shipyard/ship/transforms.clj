(ns shipyard.ship.transforms
  "Named vessels reference reusable ship classes and own their custom paint."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def valid? (m/validator schemas/ship))

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
