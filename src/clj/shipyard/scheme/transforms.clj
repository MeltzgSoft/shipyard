(ns shipyard.scheme.transforms
  "Pure scheme representation and explicit identity-based updates."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def material? (m/validator schemas/material))
(def scheme? (m/validator schemas/scheme))

(defn put-record [exists? record mode]
  (cond
    (not (scheme? record)) {:error :invalid-scheme}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= mode :create) exists?) {:error :id-exists}
    (and (= mode :update) (not exists?)) {:error :missing-scheme}
    :else {:scheme record}))

(defn delete-record [exists? _id]
  (if exists?
    {}
    {:error :missing-scheme}))
