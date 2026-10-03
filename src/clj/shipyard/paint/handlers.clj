(ns shipyard.paint.handlers
  (:refer-clojure :exclude [reset!])
  (:require [shipyard.assembly.db :as assembly]
            [shipyard.http.htmx :as htmx]
            [shipyard.loadout.transforms :as loadout]
            [shipyard.loadout.views :as ship-views]
            [shipyard.loadout.db :as loadouts]
            [shipyard.paint.db :as db]
            [shipyard.paint.strokes :as strokes]
            [shipyard.paint.transforms :as transforms]
            [shipyard.paint.views :as views]
            [shipyard.scheme.material :as material]
            [shipyard.scheme.db :as schemes]
            [shipyard.ship.db :as ships]
            [shipyard.workspace.db :as workspace]))

(defn table! [{:keys [workspace paint schemes named-ships loadouts]} {:keys [params]}]
  (workspace/update-workspace! workspace :ships assoc :customize-page (get params "page"))
  (htmx/fragment (ship-views/customize-table (vals (ships/listing! named-ships))
                                             (vals (:loadouts (loadouts/snapshot! loadouts)))
                                             (vals (schemes/listing! schemes))
                                             (get-in @(:state paint) [:draft :ship-id]) (get params "page"))))

(defn current! [{:keys [workspace paint schemes loadouts named-ships] :as deps} {:keys [params]}]
  (let [checked (db/refresh! deps)
        record (when-not (:error checked) (db/record! deps))
        records (schemes/listing! schemes)
        base (schemes/palette! schemes (get-in @(:state paint) [:draft :scheme]))
        result (assembly/request! (assoc deps :assembly paint :paint-profile (or record base)) nil {:resume? (not= "1" (get params "poll"))})
        draft (:draft result)
        targets (transforms/targets (:database result) draft)
        state (workspace/workspace! workspace :ships)]
    (htmx/fragment
     (concat (views/panel {:preset-db schemes :schemes (vals records) :ships (vals (ships/listing! named-ships))
                           :classes (vals (:loadouts (loadouts/snapshot! loadouts))) :page (:customize-page state)} draft targets record
                          (or (get params "error") (:error checked)
                              (when (and (:scheme draft) (nil? base)) "Scheme unavailable. Choose another scheme; custom paint is preserved.")
                              (when (:detail-warning result) "Some details belong to a changed part or source mesh. Reset custom paint before repainting."))
                          (:prepared result) state (:flush-interval-ms paint) (material/resolve-material record))
             [[:input {:type "hidden" :data-assembly-event (pr-str (:event result))}]]))))

(defn select! [{:keys [paint schemes named-ships] :as deps} {:strs [id scheme]}]
  (cond
    (some? id)
    (if (or (= "" id) (ships/record! named-ships (parse-uuid id)))
      (do (swap! (:state paint) assoc :draft {:revision (inc (get-in @(:state paint) [:draft :revision] 0)) :ship-id (parse-uuid id)})
          (db/refresh! deps))
      {:error "That named ship is unavailable."})
    (some? scheme)
    (if (or (= "" scheme) (schemes/palette! schemes (parse-uuid scheme)))
      (if-let [ship (db/selected-ship! deps)]
        (let [saved (ships/put! named-ships (cond-> (dissoc ship :ship/scheme) (seq scheme) (assoc :ship/scheme (parse-uuid scheme))) :update)]
          (if (:error saved) {:error (:message saved)} (db/refresh! deps)))
        (do (swap! (:state paint) assoc-in [:draft :scheme] (parse-uuid scheme)) {}))
      {:error "That scheme is unavailable."})
    :else {}))

(defn create! [{:keys [named-ships loadouts schemes paint] :as deps} {:strs [name class scheme]}]
  (let [class-id (or (some-> class parse-uuid) (get-in @(:state paint) [:draft :class-id])) scheme-id (some-> scheme parse-uuid)]
    (cond
      (not (loadout/name? name)) {:error "Enter a ship name between 1 and 200 characters."}
      (nil? (get-in (loadouts/snapshot! loadouts) [:loadouts class-id])) {:error "Choose an available ship class."}
      (and (seq scheme) (nil? (schemes/palette! schemes scheme-id))) {:error "Choose an available scheme."}
      :else (let [record (cond-> {:ship/id (random-uuid) :ship/name name :ship/class class-id :ship/paint {}}
                           scheme-id (assoc :ship/scheme scheme-id))
                  result (ships/put! named-ships record :create)]
              (if (:error result) {:error (:message result)}
                  (do (swap! (:state paint) assoc :draft {:revision 0 :ship-id (:ship/id record)}) (db/refresh! deps)))))))

(defn rename! [{:keys [named-ships] {ship-lock :lock} :named-ships :as deps} {:strs [name]}]
  (locking ship-lock
    (if-let [record (when (loadout/name? name) (db/selected-ship! deps))]
      (let [result (ships/put! named-ships (assoc record :ship/name name) :update)]
        (when (:error result) {:error (:message result)}))
      {:error "Select a named ship and enter a valid name."})))

(defn reset! [{:keys [named-ships] {ship-lock :lock} :named-ships :as deps} {:strs [id confirmed]}]
  (locking ship-lock
    (if-let [ship (db/selected-ship! deps)]
      (if (and (= id (str (:ship/id ship))) (= "true" confirmed))
        (let [result (ships/put! named-ships (assoc ship :ship/paint {}) :update)]
          (when (:error result) {:error (:message result)}))
        {:error "Confirm resetting this ship's custom paint."})
      {:error "Choose a named ship first."})))

(defn stroke! [{:keys [paint] :as deps} {:keys [params]}]
  (let [result (strokes/stroke! deps params)]
    (cond
      (or (:buffered result) (:canceled result)) (htmx/fragment nil {:status 204 :headers {"X-Shipyard-Brush" "buffered"}})
      (:error result)
      (htmx/fragment
       [:span.detail__error {:role "alert" :data-brush-result "failed"}
        (or (:message result)
            (case (:error result)
              :changed-source "Source mesh changed. Reset custom paint before repainting. Nothing saved."
              :invalid-faces "Invalid stroke faces. Nothing saved."
              :invalid-material "Invalid detail material. Choose a colour and metalness/roughness between 0 and 1. Nothing saved."
              :stale-stroke "Paint selection changed, or the stroke is out of order. Reopen it before retrying."
              :stroke-in-progress "Finish the current stroke before changing detail history."
              :no-undo "No detail stroke to undo for this selection."
              :no-redo "No detail stroke to redo for this selection."
              "Stroke was not saved. Retry, or reopen Customize to restore saved details."))]
       (when (= "false" (get params "final")) {:status 409}))
      :else
      (let [scene (assembly/request! (assoc deps :assembly paint :paint-profile (db/record! deps)) nil {})
            record (db/record! deps)]
        (htmx/fragment
         (list [:span {:data-brush-result "saved"} "Details saved."]
               [:span#paint-header-status {:hx-swap-oob "outerHTML" :role "status"} "Details saved."]
               [:span#paint-face-count {:hx-swap-oob "outerHTML"} (str (reduce + 0 (map #(count (:faces %)) (vals (:scheme/details record)))) " painted faces")]
               [:input {:type "hidden" :data-assembly-event (pr-str (:event scene))}]))))))

(defn delete! [{:keys [named-ships paint]} {:strs [id confirmed]}]
  (if-not (= confirmed "true")
    {:error "Confirm deleting this named ship."}
    (let [ship-id (some-> id parse-uuid)
          result (ships/delete! named-ships ship-id)]
      (if (:error result) {:error (or (:message result) "Ship could not be deleted.")}
          (do (when (= ship-id (get-in @(:state paint) [:draft :ship-id]))
                (swap! (:state paint) update :draft #(-> % (dissoc :ship-id :name) (update :revision inc)))) {})))))
