(ns shipyard.scheme.transforms
  "Pure scheme representation and explicit identity-based updates."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def empty-store {:version 1 :schemes {}})

(def unit-number? (m/validator schemas/unit-number))
(def material? (m/validator schemas/material))
(def member? (m/validator schemas/member))
(def group? (m/validator schemas/group))
(def groups? (m/validator schemas/groups))
(def layers? (m/validator schemas/palette))
(def custom-paint? (m/validator schemas/custom-paint))
(def scheme? (m/validator schemas/scheme))
(def store? (m/validator (schemas/record-store :schemes :scheme/id schemas/scheme)))

(def ^:private instance-path? (m/validator schemas/instance-path))
(def ^:private valid-instance? (m/validator schemas/instance))

(defn instance-entry? [[path value]]
  (and (instance-path? path) (valid-instance? value)))

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
