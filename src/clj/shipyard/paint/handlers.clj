(ns shipyard.paint.handlers
  (:require [shipyard.assembly.db :as assembly]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.htmx :as htmx]
            [shipyard.loadout.transforms :as loadout]
            [shipyard.paint.db :as db]
            [shipyard.paint.strokes :as strokes]
            [shipyard.paint.transforms :as transforms]
            [shipyard.paint.views :as views]
            [shipyard.scheme.db :as schemes]
            [shipyard.workspace.db :as workspace]))

(defn current! [{:keys [workspace paint schemes] :as deps} {:keys [params]}]
  (let [result (assembly/request! (assoc deps :assembly paint) nil {:resume? (not= "1" (get params "poll"))})
        draft (:draft result) records (:schemes (schemes/snapshot! schemes))
        record (get records (:scheme draft))
        targets (transforms/targets (:database result) draft)
        state (workspace/workspace! workspace :paint)
        target (or (first (filter #(= (:target state) (:key %)) targets)) (first targets))]
    (workspace/update-workspace! workspace :paint assoc :target (:key target))
    (htmx/fragment
     (concat (views/panel (vals records) draft targets target record
                          (transforms/target-material record target)
                          (transforms/affected-paths record targets target)
                          (or (:edit-sequence state) 0)
                          (or (get params "error") (when (:scheme-warning result) "Scheme unavailable. Choose another scheme; its saved reference is preserved.")
                              (when (:detail-warning result) "Some details belong to a changed part or source mesh and are not displayed. Select that instance and Clear instance details before repainting; old details remain saved until you clear them."))
                          (:prepared result) (or (:brush-sequence state) 0))
             [[:input {:type "hidden" :data-assembly-event (pr-str (:event result))}]]))))

(defn select! [{:keys [paint schemes workspace catalog]} {:strs [id target]}]
  (cond
    (some? id)
    (if (or (= "" id) (get-in (schemes/snapshot! schemes) [:schemes (parse-uuid id)]))
      (do (swap! (:state paint) update :draft #(-> % (assoc :scheme (parse-uuid id)) (update :revision inc))) {})
      {:error "That scheme is unavailable."})
    target (if (some #(= target (:key %)) (transforms/targets (catalog/snapshot! catalog) (:draft @(:state paint))))
             (do (workspace/update-workspace! workspace :paint assoc :target target) {})
             {:error "That instance is no longer in the paint preview. Choose another target."})
    :else {}))

(defn create! [{:keys [schemes paint]} {:strs [name]}]
  (if-not (loadout/name? name)
    {:error "Enter a scheme name between 1 and 200 characters."}
    (let [record {:scheme/id (random-uuid) :scheme/name name :scheme/roles {}}
          result (schemes/put! schemes record :create)]
      (if (:error result)
        {:error (:message result)}
        (do (swap! (:state paint) assoc-in [:draft :scheme] (:scheme/id record)) {})))))

(defn rename! [{:keys [schemes paint] {scheme-state :state} :schemes} {:strs [name]}]
  (locking scheme-state
    (let [id (get-in @(:state paint) [:draft :scheme]) record (get-in (schemes/snapshot! schemes) [:schemes id])]
      (if (and record (loadout/name? name))
        (let [result (schemes/put! schemes (assoc record :scheme/name name) :update)]
          (when (:error result) {:error (:message result)}))
        {:error "Select a scheme and enter a valid name."}))))

(defn default! [{:keys [schemes paint workspace catalog] {scheme-state :state} :schemes} _]
  (locking scheme-state
    (let [draft (:draft @(:state paint)) record (get-in (schemes/snapshot! schemes) [:schemes (:scheme draft)])
          target (first (filter #(= (:key %) (:target (workspace/workspace! workspace :paint)))
                                (transforms/targets (catalog/snapshot! catalog) draft)))
          result (when record (transforms/edit-record record target nil true))]
      (if (and result (not (:error result)))
        (let [saved (schemes/put! schemes (:scheme result) :update)]
          (when (:error saved) {:error (:message saved)}))
        {:error "Select an individual instance to restore its role default."}))))

(defn material! [deps {:keys [params]}]
  (let [result (db/edit! deps params)]
    (htmx/fragment
     (if (:error result)
       [:span.detail__error {:role "alert"} (or (:message result) "Material was not saved. Check the values and retry.")]
       [:span "Material saved."]))))

(defn stroke! [{:keys [paint] :as deps} {:keys [params]}]
  (let [result (strokes/stroke! deps params)]
    (htmx/fragment
     (if (:error result)
       [:span.detail__error {:role "alert"}
        (or (:message result)
            (case (:error result)
              :changed-source "Source mesh changed. Clear instance details before repainting. Nothing saved."
              :invalid-faces "Invalid or oversized stroke. Use a smaller brush or shorter stroke (maximum 1,024 faces). Nothing saved."
              :invalid-material "Invalid detail material. Choose a colour and metalness/roughness between 0 and 1. Nothing saved."
              :paint-limit "Scheme detail limit reached (100,000 faces). Erase or clear some details first."
              :stale-stroke "Paint selection changed. Reopen it before retrying."
              :no-undo "No detail stroke to undo for this selection."
              :no-redo "No detail stroke to redo for this selection."
              "Stroke was not saved. Retry, or reopen Paint to restore saved details."))]
       (let [scene (assembly/request! (assoc deps :assembly paint) nil {})]
         (list [:span "Details saved."]
               [:input {:type "hidden" :data-assembly-event (pr-str (:event scene))}]))))))
