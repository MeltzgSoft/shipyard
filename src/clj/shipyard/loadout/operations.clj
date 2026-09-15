(ns shipyard.loadout.operations
  "Authoritative named-loadout operations.

  The store owns bytes; this namespace owns the rule that persisted catalog ids
  must be checked again before a saved ship can affect an active draft."
  (:require [clojure.string :as str]
            [shipyard.assembly.model :as model]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout.store :as store])
  (:import [java.util UUID]))

(defn- name! [name]
  (let [name (some-> name str/trim)]
    (when (and (string? name) (not-empty name) (<= (count name) 160)) name)))

(defn- draft-error [database draft available]
  (let [root (catalog/part database (:hull draft))
        slots (when (:hull draft) (model/slots database (:hull draft) (:assignments draft)))
        ids (cons (:hull draft) (vals (:assignments draft)))]
    (cond
      (nil? (:hull draft)) :no-draft
      (model/root-error root) :stale-catalog
      (some #(not (available %)) ids) :unavailable-mesh
      (seq (:errors slots)) :invalid-assignments)))

(defn list! [loadout-store] (store/loadouts loadout-store))

(defn save!
  "Persist a complete, current draft under `name`; errors never mutate it."
  [loadout-store database draft available name]
  (if-let [name (name! name)]
    (if-let [error (draft-error database draft available)]
      {:error error}
      (let [loadout #:loadout{:id (UUID/randomUUID) :name name :hull (:hull draft)
                              :slots (:assignments draft) :scheme nil :thumb nil}]
        (store/replace! loadout-store (conj (store/loadouts loadout-store) loadout))
        {:loadout loadout}))
    {:error :invalid-name}))

(defn- found [loadout-store id]
  (some #(when (= (:loadout/id %) id) %) (store/loadouts loadout-store)))

(defn load!
  "Restore `id` only when its current catalog facts still form a valid draft."
  [loadout-store database active-draft available id]
  (if-let [loadout (found loadout-store id)]
    (let [candidate {:revision (:revision active-draft) :hull (:loadout/hull loadout)
                     :assignments (:loadout/slots loadout)}]
      (if-let [error (draft-error database candidate available)]
        {:error error :draft active-draft}
        {:loadout loadout :draft (update candidate :revision inc)}))
    {:error :missing-loadout :draft active-draft}))

(defn duplicate!
  "Copy a currently valid saved loadout under a new identity and name."
  [loadout-store database available id name]
  (if-let [name (name! name)]
    (if-let [original (found loadout-store id)]
      (let [draft {:hull (:loadout/hull original) :assignments (:loadout/slots original)}]
        (if-let [error (draft-error database draft available)]
          {:error error}
          (let [copy (assoc original :loadout/id (UUID/randomUUID) :loadout/name name)]
            (store/replace! loadout-store (conj (store/loadouts loadout-store) copy))
            {:loadout copy})))
      {:error :missing-loadout})
    {:error :invalid-name}))
