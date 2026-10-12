(ns shipyard.fleet.handlers
  "Fleet Browser admission and server-owned table/editor navigation."
  (:require [shipyard.fleet.db :as fleet]
            [integrant.core :as ig]
            [shipyard.assembly.db :as assembly]
            [shipyard.assembly.transforms :as assembly-transforms]
            [shipyard.catalog.db :as catalog]
            [shipyard.fleet.editor-transforms :as editor]
            [shipyard.library.index :as index]
            [datalevin.core :as d]
            [shipyard.loadout.model :as loadout-model]
            [shipyard.store.db :as store]
            [shipyard.fleet.plan :as plan]
            [shipyard.fleet.views :as views]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.views :as shell]
            [shipyard.workspace.db :as workspace]))

(defmethod ig/init-key :shipyard.fleet.handlers/preview [_ _]
  {:state (atom {:draft assembly-transforms/empty-draft :sequence 0 :reset-sequence 0 :root nil :scene {}})})

(defn facade [{:keys [fleets catalog]}]
  (or fleets {:store (:store catalog) :catalog catalog}))

(defn- entries! [deps]
  (let [db (facade deps)]
    (->> (:fleets (fleet/snapshot! db))
         (vals)
         (sort-by (juxt :fleet/name #(str (:fleet/id %))))
         (mapv (fn [record] {:fleet record :plan (plan/print-plan (fleet/export-snapshot! db (:fleet/id record)))})))))

(defn- preview-result! [{:keys [workspace fleet-preview library catalog] :as deps} snapshot poll?]
  (when fleet-preview
    (let [class-id (:preview-class (workspace/workspace! workspace :fleets))
          record (when (contains? (get-in snapshot [:fleet :fleet/entries]) class-id)
                   (get-in snapshot [:classes class-id]))
          state (:state fleet-preview)]
      (locking state
        (let [draft (if record (loadout-model/from-record record (get-in @state [:draft :revision]) :preview)
                        assembly-transforms/empty-draft)
              changed? (not= (dissoc draft :revision) (dissoc (:draft @state) :revision))
              validated (when record
                          (loadout-model/validate (catalog/assembly-snapshot! catalog) draft
                                                  (set (filter #(index/fresh-source-file! library %)
                                                               (cons (:hull draft) (vals (:assignments draft)))))))]
          (when (or changed? (:error validated))
            (swap! state assoc :draft (if (:error validated) assembly-transforms/empty-draft draft)
                   :root (index/root! library)))
          (cond-> (assembly/request! (assoc deps :assembly fleet-preview :paint-profile nil) nil
                                     {:resume? (or changed? (not poll?))})
            (:error validated) (assoc :preview-error (str "Cannot preview this class: " (name (:error validated)) ". Restore its parts or repair the saved class."))))))))

(defn- classes! [{:keys [catalog] :as deps}]
  (let [database (:store (facade deps))]
    (store/read! database
                 (fn [db]
                   (into {} (keep (fn [id]
                                    (when-let [record (store/record-value db :loadouts id)] [id record])))
                         (d/q '[:find [?id ...] :in $ ?library
                                :where [?lib :library/id ?library] [?class :loadout/library ?lib]
                                [?class :loadout/id ?id]] db (:library @(:state catalog))))))))

(defn current! [{:keys [workspace] :as deps} {:keys [params headers fleet-export-result]}]
  (let [{:keys [view selection filters]} (workspace/workspace! workspace :fleets)
        snapshot (when selection (fleet/export-snapshot! (facade deps) selection))
        record (:fleet snapshot)]
    (cond
      (= "fleet-results" (get headers "hx-target"))
      (do (workspace/remember! workspace :fleets params)
          (htmx/fragment (views/results (entries! deps) (:filters (workspace/workspace! workspace :fleets)))))
      (and (= view :editor) record)
      (if (and (= "1" (get params "poll"))
               (or (not (editor/selected? selection params))
                   (not= (str (:preview-class (workspace/workspace! workspace :fleets))) (get params "class-id"))))
        {:status 204 :headers {} :body ""}
        (let [preview (preview-result! deps snapshot (= "1" (get params "poll")))
              projection (plan/print-plan snapshot)
              classes (classes! deps)]
          (htmx/fragment (list (views/editor record {:plan projection :classes classes :parts (:parts snapshot)
                                                     :selected-class (:preview-class (workspace/workspace! workspace :fleets))
                                                     :error (or (get params "error") (:preview-error preview))
                                                     :message (get params "message") :preview preview
                                                     :export-controls (when-let [panel (:fleet-export-panel deps)]
                                                                        (panel deps record fleet-export-result))})
                               (when preview [:input {:type "hidden" :data-assembly-event (pr-str (:event preview))}])))))
      :else
      (do (when (= view :editor) (workspace/update-workspace! workspace :fleets assoc :view :table :selection nil))
          (htmx/fragment (list (shell/detail-empty)
                               (views/table (entries! deps) filters (or (get params "error")
                                                                        (when (and selection (nil? record)) "This fleet is unavailable in the selected library."))))
                         {:events {:clear nil}})))))

(defn open! [{:keys [workspace] :as deps} {:keys [params]}]
  (workspace/remember! workspace :fleets params)
  (if-let [record (fleet/record! (facade deps) (parse-uuid (get params "id")))]
    (do (workspace/update-workspace! workspace :fleets assoc :view :editor :selection (:fleet/id record) :preview-class nil)
        (current! deps {}))
    (current! deps {:params {"error" "This fleet is unavailable in the selected library."}})))

(defn create! [{:keys [workspace] :as deps} {:keys [params]}]
  (workspace/remember! workspace :fleets params)
  (let [result (fleet/create! (facade deps) (get params "name"))]
    (if (:error result)
      (assoc (current! deps {:params {"error" (or (:message result) "Could not create this fleet. Enter a name and select a library.")}}) :status 422)
      (let [record (:fleet result)]
        (workspace/update-workspace! workspace :fleets assoc :view :editor :selection (:fleet/id record) :preview-class nil)
        (current! deps {})))))

(defn edit! [{:keys [workspace] :as deps} {:keys [params]}]
  (let [selection (:selection (workspace/workspace! workspace :fleets))
        action (keyword (get params "action"))
        db (facade deps)]
    (if-not (editor/selected? selection params)
      (assoc (current! deps {:params {"error" "This fleet selection has changed. Reopen the list before editing."}}) :status 409)
      (let [result (try
                     (store/write! (:store db)
                                   (fn [conn]
                                     (let [db (assoc-in db [:store :conn] conn)
                                           record (fleet/record! db selection)]
                                       (cond
                                         (nil? record) {:error "This fleet is unavailable in the selected library."}
                                         (= action :default) (fleet/use-default! db selection)
                                         (= action :delete) (if (= "yes" (get params "confirm"))
                                                              (fleet/delete! db selection)
                                                              {:error "Confirm deletion of this fleet."})
                                         :else (let [result (editor/change record action params)]
                                                 (if (:error result) result (fleet/put! db (:fleet result) :update)))))))
                     (catch Exception e {:error (ex-message e)}))]
        (if (:error result)
          (assoc (current! deps {:params {"error" (or (:message result) (str (:error result)))}}) :status 422)
          (do (when (= action :delete)
                (workspace/update-workspace! workspace :fleets assoc :view :table :selection nil :preview-class nil))
              (when (= action :class-remove)
                (when (= (get params "class-id") (str (:preview-class (workspace/workspace! workspace :fleets))))
                  (workspace/update-workspace! workspace :fleets dissoc :preview-class)))
              (current! deps {:params {"message" "Saved fleet."}})))))))

(defn preview! [{:keys [workspace] :as deps} {:keys [params]}]
  (let [selection (:selection (workspace/workspace! workspace :fleets))
        record (fleet/record! (facade deps) selection)
        class-id (some-> (get params "class-id") parse-uuid)]
    (if (and (editor/selected? selection params) (contains? (:fleet/entries record) class-id))
      (do (workspace/update-workspace! workspace :fleets assoc :preview-class class-id)
          (current! deps {}))
      (assoc (current! deps {:params {"error" "Choose a class in the current fleet to preview."}}) :status 409))))
