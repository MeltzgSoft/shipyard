(ns shipyard.scheme.transforms
  "Pure scheme representation and explicit identity-based updates."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def material? (m/validator schemas/material))
(def member? (m/validator schemas/member))
(def scheme? (m/validator schemas/scheme))

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
