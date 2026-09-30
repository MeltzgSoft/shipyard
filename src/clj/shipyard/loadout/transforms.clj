(ns shipyard.loadout.transforms
  "Pure durable representation. Catalog compatibility belongs to operations."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]
            [shipyard.loadout.identity :as identity]))

(def name? identity/name?)

(def loadout? (m/validator schemas/loadout))

(defn put-record
  "Create and replace are explicit; names never select the record to update."
  [store record mode]
  (cond
    (not (loadout? record)) {:error :invalid-loadout}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= :create mode) (contains? (:loadouts store) (:loadout/id record))) {:error :id-exists}
    (and (= :update mode) (not (contains? (:loadouts store) (:loadout/id record)))) {:error :missing-loadout}
    :else {:store (assoc-in store [:loadouts (:loadout/id record)] record) :loadout record}))

(defn delete-record [store id]
  (cond
    (not (uuid? id)) {:error :invalid-loadout-id}
    (not (contains? (:loadouts store) id)) {:error :missing-loadout}
    :else {:store (update store :loadouts dissoc id) :deleted id}))
