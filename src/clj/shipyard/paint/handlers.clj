(ns shipyard.paint.handlers
  (:refer-clojure :exclude [reset!])
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
            [shipyard.ship.db :as ships]
            [shipyard.workspace.db :as workspace]))

(defn current! [{:keys [workspace paint schemes loadouts named-ships] :as deps} {:keys [params]}]
  (let [checked (db/refresh! deps)
        record (when-not (:error checked) (db/record! deps))
        records (schemes/listing! schemes)
        base (schemes/palette! schemes (get-in @(:state paint) [:draft :scheme]))
        result (assembly/request! (assoc deps :assembly paint :paint-profile (or record base)) nil {:resume? (not= "1" (get params "poll"))})
        draft (:draft result)
        targets (transforms/targets (:database result) draft record)
        state (workspace/workspace! workspace :ships)
        target (or (first (filter #(= (:target state) (:key %)) targets)) (first targets))]
    (workspace/update-workspace! workspace :ships assoc :target (:key target))
    (htmx/fragment
     (concat (views/panel {:preset-db schemes :schemes (vals records) :ships (vals (ships/listing! named-ships))
                           :classes (vals (:loadouts (loadouts/snapshot! loadouts)))} draft targets target record
                          (transforms/target-material record target)
                          (transforms/affected-paths record targets target)
                          (or (:edit-sequence state) 0)
                          (or (get params "error") (:error checked)
                              (when (and (:scheme draft) (nil? base)) "Scheme unavailable. Choose another scheme; custom paint is preserved.")
                              (when (:detail-warning result) "Some details belong to a changed part or source mesh. Select that instance and Clear instance details before repainting."))
                          (:prepared result) (:anchor-target state) state (:flush-interval-ms paint))
             [[:input {:type "hidden" :data-assembly-event (pr-str (:event result))}]]))))

(defn select! [{:keys [paint schemes named-ships workspace catalog] :as deps} {:strs [id target scheme]}]
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
    target (if (some #(= target (:key %)) (transforms/targets (catalog/assembly-snapshot! catalog) (:draft @(:state paint)) (db/record! deps)))
             (do (workspace/update-workspace! workspace :ships assoc :target target :anchor-target
                                              (if (str/starts-with? target "[") target (:anchor-target (workspace/workspace! workspace :ships)))) {})
             {:error "That instance is no longer in the paint preview. Choose another target."})
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

(defn default! [{:keys [paint workspace catalog] {ship-lock :lock} :named-ships :as deps} _]
  (locking ship-lock
    (let [draft (:draft @(:state paint)) record (db/record! deps)
          target (first (filter #(= (:key %) (:target (workspace/workspace! workspace :ships)))
                                (transforms/targets (catalog/assembly-snapshot! catalog) draft record)))
          result (when record (transforms/edit-record record target nil true))]
      (if (and result (not (:error result)))
        (let [saved (db/save! deps (:scheme result))]
          (when (:error saved) {:error (:message saved)}))
        {:error "Select a paint target to restore its inherited material."}))))

(defn material! [{:keys [paint workspace catalog] :as deps} {:keys [params]}]
  (let [result (db/edit! deps params)]
    (htmx/fragment
     (if (:error result)
       [:span.detail__error {:role "alert"} (or (:message result) "Material was not saved. Check the values and retry.")]
       (let [draft (:draft @(:state paint)) record (db/record! deps)
             targets (transforms/targets (catalog/assembly-snapshot! catalog) draft record)
             target (first (filter #(= (:target (workspace/workspace! workspace :ships)) (:key %)) targets))
             tree (second (views/target-tree record targets target))
             scene (assembly/request! (assoc deps :assembly paint :paint-profile record) nil {})]
         (list [:span "Material saved."]
               (assoc-in tree [1 :hx-swap-oob] "outerHTML")
               [:input {:type "hidden" :data-assembly-event (pr-str (:event scene))}]))))))

(defn group! [{:keys [paint catalog workspace] {ship-lock :lock} :named-ships :as deps} action params]
  (locking ship-lock
    (let [draft (:draft @(:state paint)) record (db/record! deps)
          id (if (= action :create) (random-uuid) (some-> (get params "group") (parse-uuid)))
          selected (groups/members (transforms/targets (catalog/assembly-snapshot! catalog) draft) (get params "members"))
          result (groups/change record action id (get params "name") selected (get params "direction"))
          saved (if (:error result) result (db/save! deps (:scheme result)))]
      (if (:error saved) {:error (or (:message saved) (:error saved))}
          (when (= action :create)
            (workspace/update-workspace! workspace :ships assoc :target (str "group/" id)))))))

(defn stroke! [{:keys [paint] :as deps} {:keys [params]}]
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
      (let [scene (assembly/request! (assoc deps :assembly paint :paint-profile (db/record! deps)) nil {})
            record (db/record! deps)]
        (htmx/fragment
         (list [:span {:data-brush-result "saved"} "Details saved."]
               [:span#paint-header-status {:hx-swap-oob "outerHTML" :role "status"} "Details saved."]
               [:span#paint-face-count {:hx-swap-oob "outerHTML"} (str (reduce + 0 (map #(count (:faces %)) (vals (:scheme/details record)))) " painted faces")]
               [:input {:type "hidden" :data-assembly-event (pr-str (:event scene))}]))))))

(defn delete! [{:keys [named-ships paint]} {:strs [id confirmed]}]
  (let [selected (get-in @(:state paint) [:draft :ship-id])]
    (if-not (and (= confirmed "true") (= id (str selected)))
      {:error "Select the named ship and confirm its deletion."}
      (let [result (ships/delete! named-ships selected)]
        (if (:error result) {:error (or (:message result) "Ship could not be deleted.")}
            (do (swap! (:state paint) assoc :draft {:revision 0}) {}))))))
