(ns shipyard.paint.db
  "Named-ship paint coordination over the shared Ship Browser preview."
  (:require [integrant.core :as ig]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.loadout.model :as loadout]
            [shipyard.loadout.db :as classes]
            [shipyard.ship.db :as ships]
            [shipyard.paint.job :as job]
            [shipyard.scheme.db :as schemes]))

(defmethod ig/init-key :shipyard.paint/db [_ {:keys [paint/flush-interval-ms preview] :or {flush-interval-ms 120}}]
  (when-not (pos-int? flush-interval-ms)
    (throw (ex-info "paint/flush-interval-ms must be a positive integer" {})))
  {:state (:state preview)
   :flush-interval-ms flush-interval-ms
   :face-cache (atom {})})

(defn selected-ship! [{:keys [paint named-ships]}]
  (ships/record! named-ships (get-in @(:state paint) [:draft :ship-id])))

(defn record! [{:keys [schemes] :as deps}]
  (when-let [ship (selected-ship! deps)]
    (job/editor-record ship (schemes/palette! schemes (:ship/scheme ship)))))

(defn refresh!
  "Resolve the named vessel's class afresh. Class changes retain source-bound paint."
  [{:keys [paint loadouts library] :as deps}]
  (let [draft (:draft @(:state paint)) ship (selected-ship! deps)
        class-id (or (:ship/class ship) (:class-id draft))
        class (get-in (classes/snapshot! loadouts) [:loadouts class-id])]
    (cond
      (and (:ship-id draft) (nil? ship)) {:error "This named ship was deleted. Choose another ship or create one."}
      (and class-id (nil? class)) {:error "This ship class is unavailable. Restore it before painting; custom paint is retained."}
      class (do (swap! (:state paint) assoc :root (index/root! library) :draft
                       (cond-> {:revision (:revision draft) :hull (:loadout/hull class) :assignments (:loadout/slots class)
                                :class-id class-id :class-name (:loadout/name class)
                                :scheme (if ship (:ship/scheme ship) (:scheme draft))}
                         ship (assoc :ship-id (:ship/id ship) :name (:ship/name ship))))
                {}))))

(defn save! [{:keys [named-ships paint loadouts] {ship-lock :lock} :named-ships :as deps} profile]
  (locking ship-lock
    (let [ship (selected-ship! deps) draft (:draft @(:state paint))
          class (get-in (classes/snapshot! loadouts) [:loadouts (:class-id draft)])]
      (if (and ship (= (:scheme/id profile) (:ship/id ship))
               (= (:hull draft) (:loadout/hull class)) (= (:assignments draft) (:loadout/slots class)))
        (ships/put! named-ships (assoc ship :ship/paint (job/from-profile profile)) :update)
        {:error :stale-edit :message "The ship or its class changed. Reopen it before retrying."}))))

(defn transfer! [{:keys [paint library catalog loadouts]} source scheme]
  (let [class-id (or (get-in @(:state source) [:draft :class-id]) (get-in @(:state source) [:draft :loadout-id]))
        class (get-in (classes/snapshot! loadouts) [:loadouts class-id])
        draft (when class {:revision (inc (get-in @(:state paint) [:draft :revision] 0))
                           :hull (:loadout/hull class) :assignments (:loadout/slots class)
                           :class-id class-id :class-name (:loadout/name class) :scheme scheme})
        available (set (filter #(index/fresh-source-file! library %) (cons (:hull draft) (vals (:assignments draft)))))
        result (if class (loadout/validate (catalog/assembly-snapshot! catalog) draft available)
                   {:error :missing-class})]
    (when-not (:error result)
      (swap! (:state paint) assoc :draft draft :root (index/root! library)))
    result))
