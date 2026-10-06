(ns shipyard.loadout.transforms
  "Pure durable representation. Catalog compatibility belongs to operations."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]
            [shipyard.loadout.identity :as identity]))

(def name? identity/name?)

(def loadout? (m/validator schemas/loadout))

(defn put-record
  "Validate a targeted create/update against active identity existence. Names do not select records."
  [exists? record mode]
  (cond
    (not (loadout? record)) {:error :invalid-loadout}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= :create mode) exists?) {:error :id-exists}
    (and (= :update mode) (not exists?)) {:error :missing-loadout}
    :else {:loadout record}))

(defn delete-record [exists? id]
  (cond
    (not (uuid? id)) {:error :invalid-loadout-id}
    (not exists?) {:error :missing-loadout}
    :else {:deleted id}))
