(ns shipyard.loadout.operations
  "Authoritative operations; explicit preview and edit state never share a draft cell."
  (:require [integrant.core :as ig]
            [shipyard.assembly.transforms :as assembly]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.loadout.db :as store]
            [shipyard.loadout.model :as model]
            [shipyard.loadout.transforms :as transforms]))

(defmethod ig/init-key :shipyard.loadout.operations/preview [_ _]
  {:state (atom {:draft assembly/empty-draft :sequence 0 :root nil :scene {}})})

(defn- validate! [{:keys [catalog library]} draft]
  (model/validate (catalog/snapshot! catalog) draft
                  (set (filter #(index/fresh-source-file! library %)
                               (cons (:hull draft) (vals (:assignments draft)))))))

(defn save! [{:keys [loadouts] {state :state} :assembly :as deps} revision name]
  (locking state
    (let [draft (:draft @state)
          error (cond (not= revision (:revision draft)) :stale-revision
                      (not (transforms/name? name)) :invalid-name)
          validated (when-not error (validate! deps draft))]
      (if-let [error (or error (:error validated))]
        {:error error}
        (let [id (or (:loadout-id draft) (random-uuid))
              record (model/to-record draft id name)
              result (store/put! loadouts record (if (:loadout-id draft) :update :create))]
          (when-not (:error result)
            (swap! state assoc :draft (assoc draft :loadout-id id :name name :revision (inc revision))))
          result)))))

(defn transfer!
  "Validate first. Preview targets its own cell; Edit and Duplicate target Assemble.
  Neither selection nor transfer performs a durable write."
  [{:keys [loadouts assembly preview library] :as deps} id mode]
  (let [target (if (= :preview mode) preview assembly)
        state (:state target)]
    (locking state
      (if-let [record (get-in (store/snapshot! loadouts) [:loadouts id])]
        (let [draft (model/from-record record (inc (get-in @state [:draft :revision])) mode)
              result (validate! deps draft)]
          (when-not (:error result)
            (swap! state assoc :draft draft :root (index/root! library)))
          result)
        {:error :missing-loadout}))))

(defn list! [{:keys [catalog loadouts]} filters]
  (model/listing (catalog/snapshot! catalog) (:loadouts (store/snapshot! loadouts)) filters))
