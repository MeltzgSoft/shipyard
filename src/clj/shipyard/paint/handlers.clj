(ns shipyard.paint.handlers
  (:require [clojure.string :as str]
            [shipyard.assembly.db :as assembly]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.htmx :as htmx]
            [shipyard.loadout.transforms :as loadout]
            [shipyard.loadout.db :as loadouts]
            [shipyard.paint.db :as db]
            [shipyard.paint.groups :as groups]
            [shipyard.paint.strokes :as strokes]
            [shipyard.paint.transforms :as transforms]
            [shipyard.paint.views :as views]
            [shipyard.scheme.db :as schemes]
            [shipyard.workspace.db :as workspace]))

(defn current! [{:keys [workspace paint schemes loadouts] :as deps} {:keys [params]}]
  (let [result (assembly/request! (assoc deps :assembly paint) nil {:resume? (not= "1" (get params "poll"))})
        draft (:draft result) records (:schemes (schemes/snapshot! schemes))
        record (when-let [record (get records (:scheme draft))]
                 (assoc record :referenced-ships (mapv :loadout/name
                                                       (filter #(= (:scheme draft) (:loadout/scheme %))
                                                               (vals (:loadouts (loadouts/snapshot! loadouts)))))))
        targets (transforms/targets (:database result) draft record)
        state (workspace/workspace! workspace :paint)
        target (or (first (filter #(= (:target state) (:key %)) targets)) (first targets))]
    (workspace/update-workspace! workspace :paint assoc :target (:key target))
    (htmx/fragment
     (concat (views/panel (vals records) draft targets target record
                          (transforms/target-material record target)
                          (transforms/affected-paths record targets target)
                          (or (:edit-sequence state) 0)
                          (or (get params "error") (when (:scheme-warning result) "Scheme unavailable. Choose another scheme; its saved reference is preserved.")
                              (when (:detail-warning result) "Some details belong to a changed part or source mesh. Select that instance and Clear instance details before repainting."))
                          (:prepared result) (:anchor-target state) state (:flush-interval-ms paint))
             [[:input {:type "hidden" :data-assembly-event (pr-str (:event result))}]]))))

(defn select! [{:keys [paint schemes workspace catalog]} {:strs [id target]}]
  (cond
    (some? id)
    (if (or (= "" id) (get-in (schemes/snapshot! schemes) [:schemes (parse-uuid id)]))
      (do (swap! (:state paint) update :draft #(-> % (assoc :scheme (parse-uuid id)) (update :revision inc))) {})
      {:error "That scheme is unavailable."})
    target (if (some #(= target (:key %)) (transforms/targets (catalog/snapshot! catalog) (:draft @(:state paint))
                                                              (get-in (schemes/snapshot! schemes) [:schemes (get-in @(:state paint) [:draft :scheme])])))
             (do (workspace/update-workspace! workspace :paint assoc :target target :anchor-target
                                              (if (str/starts-with? target "[") target (:anchor-target (workspace/workspace! workspace :paint)))) {})
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
                                (transforms/targets (catalog/snapshot! catalog) draft record)))
          result (when record (transforms/edit-record record target nil true))]
      (if (and result (not (:error result)))
        (let [saved (schemes/put! schemes (:scheme result) :update)]
          (when (:error saved) {:error (:message saved)}))
        {:error "Select an individual instance to restore its inherited material."}))))

(defn material! [{:keys [paint workspace catalog schemes] :as deps} {:keys [params]}]
  (let [result (db/edit! deps params)]
    (htmx/fragment
     (if (:error result)
       [:span.detail__error {:role "alert"} (or (:message result) "Material was not saved. Check the values and retry.")]
       (let [draft (:draft @(:state paint)) record (get-in (schemes/snapshot! schemes) [:schemes (:scheme draft)])
             targets (transforms/targets (catalog/snapshot! catalog) draft record)
             target (first (filter #(= (:target (workspace/workspace! workspace :paint)) (:key %)) targets))
             tree (second (views/target-tree record targets target))]
         (list [:span "Material saved."]
               (assoc-in tree [1 :hx-swap-oob] "outerHTML")))))))

(defn group! [{:keys [schemes paint catalog workspace] {scheme-state :state} :schemes} action params]
  (locking scheme-state
    (let [draft (:draft @(:state paint)) record (get-in (schemes/snapshot! schemes) [:schemes (:scheme draft)])
          id (if (= action :create) (random-uuid) (some-> (get params "group") (parse-uuid)))
          selected (groups/members (transforms/targets (catalog/snapshot! catalog) draft) (get params "members"))
          result (groups/change record action id (get params "name") selected (get params "direction"))
          saved (if (:error result) result (schemes/put! schemes (:scheme result) :update))]
      (if (:error saved) {:error (or (:message saved) (:error saved))}
          (when (= action :create)
            (workspace/update-workspace! workspace :paint assoc :target (str "group/" id)))))))

(defn stroke! [{:keys [paint schemes] :as deps} {:keys [params]}]
  (let [result (strokes/stroke! deps params)]
    (cond
      (or (:buffered result) (:canceled result)) (htmx/fragment nil {:status 204 :headers {"X-Shipyard-Brush" "buffered"}})
      (:error result)
      (htmx/fragment
       [:span.detail__error {:role "alert" :data-brush-result "failed"}
        (or (:message result)
            (case (:error result)
              :changed-source "Source mesh changed. Clear instance details before repainting. Nothing saved."
              :invalid-faces "Invalid stroke faces. Nothing saved."
              :invalid-material "Invalid detail material. Choose a colour and metalness/roughness between 0 and 1. Nothing saved."
              :stale-stroke "Paint selection changed, or the stroke is out of order. Reopen it before retrying."
              :stroke-in-progress "Finish the current stroke before changing detail history."
              :no-undo "No detail stroke to undo for this selection."
              :no-redo "No detail stroke to redo for this selection."
              "Stroke was not saved. Retry, or reopen Paint to restore saved details."))]
       (when (= "false" (get params "final")) {:status 409}))
      :else
      (let [scene (assembly/request! (assoc deps :assembly paint) nil {})
            record (get-in (schemes/snapshot! schemes) [:schemes (get-in @(:state paint) [:draft :scheme])])]
        (htmx/fragment
         (list [:span {:data-brush-result "saved"} "Details saved."]
               [:span#paint-header-status {:hx-swap-oob "outerHTML" :role "status"} "Details saved."]
               [:span#paint-face-count {:hx-swap-oob "outerHTML"} (str (reduce + 0 (map #(count (:faces %)) (vals (:scheme/details record)))) " painted faces")]
               [:input {:type "hidden" :data-assembly-event (pr-str (:event scene))}]))))))

(defn delete! [{:keys [schemes paint]} {:strs [id confirmed]}]
  (let [selected (get-in @(:state paint) [:draft :scheme])]
    (if-not (and (= confirmed "true") (= id (str selected)))
      {:error "Select the scheme and confirm its deletion."}
      (let [result (schemes/delete! schemes selected)]
        (if (:error result) {:error (or (:message result) "Scheme could not be deleted.")}
            (do (swap! (:state paint) update :draft #(-> % (dissoc :scheme) (update :revision inc))) {}))))))
