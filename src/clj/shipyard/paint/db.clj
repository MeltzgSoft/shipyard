(ns shipyard.paint.db
  "Independent paint preview and serialized commit coordination."
  (:require [integrant.core :as ig]
            [shipyard.assembly.transforms :as assembly]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.loadout.model :as loadout]
            [shipyard.paint.transforms :as transforms]
            [shipyard.scheme.db :as schemes]
            [shipyard.workspace.db :as workspace]))

(defmethod ig/init-key :shipyard.paint/db [_ _]
  {:state (atom {:draft assembly/empty-draft :sequence 0 :root nil :scene {}})
   :face-cache (atom nil)})

(defn transfer! [{:keys [paint library catalog]} source]
  (let [draft (:draft @(:state source))
        available (set (filter #(index/fresh-source-file! library %)
                               (cons (:hull draft) (vals (:assignments draft)))))
        result (loadout/validate (catalog/snapshot! catalog) draft available)]
    (when-not (:error result)
      (swap! (:state paint) assoc :draft (-> draft (dissoc :loadout-id :name) (update :revision inc))
             :root (index/root! library)))
    result))

(defn edit! [{:keys [workspace paint schemes catalog] {scheme-state :state} :schemes} {:strs [id target sequence] :as params}]
  (let [state (workspace/workspace! workspace :paint)
        selected (get-in @(:state paint) [:draft :scheme])
        request-sequence (when (string? sequence) (parse-long sequence))
        targets (transforms/targets (catalog/snapshot! catalog) (:draft @(:state paint)))
        selected-target (first (filter #(= target (:key %)) targets))]
    (if (or (not= (str selected) id) (not= target (:target state))
            (nil? request-sequence) (<= request-sequence (or (:edit-sequence state) 0)))
      {:error :stale-edit :message "This paint selection changed. Reopen it before retrying."}
      (locking scheme-state
        (if-let [record (get-in (schemes/snapshot! schemes) [:schemes selected])]
          (let [result (transforms/edit-record record selected-target (transforms/parse-material params)
                                               (= "true" (get params "clear")))
                saved (if (:error result) result (schemes/put! schemes (:scheme result) :update))]
            ;; Admit each submitted sequence once, including failed writes.
            (workspace/update-workspace! workspace :paint assoc :edit-sequence request-sequence)
            saved)
          {:error :missing-scheme :message "This scheme is missing. Choose another scheme."})))))
