(ns shipyard.ship.transforms
  "Named vessels reference reusable ship classes and own their custom paint."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def valid? (m/validator schemas/ship))

(defn put-record [exists? record mode]
  (cond
    (not (valid? record)) {:error :invalid-ship}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= mode :create) exists?) {:error :id-exists}
    (and (= mode :update) (not exists?)) {:error :missing-ship}
    :else {:ship record}))

(defn delete-record [exists? id]
  (if exists?
    {:deleted id}
    {:error :missing-ship}))
