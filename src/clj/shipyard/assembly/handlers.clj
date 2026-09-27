(ns shipyard.assembly.handlers
  "Thin Ring orchestration; transport validation is in routes."
  (:require [clojure.edn :as edn]
            [shipyard.assembly.db :as db]
            [shipyard.assembly.views :as views]
            [shipyard.assembly.model :as model]
            [shipyard.workspace.transforms :as workspace-transforms]
            [shipyard.http.htmx :as htmx]
            [shipyard.loadout.operations :as loadouts]
            [shipyard.loadout.model :as loadout-model]
            [shipyard.scheme.db :as schemes]
            [shipyard.workspace.db :as workspace]
            [shipyard.workspace.views :as workspace-views]))

(defn- response [{:keys [workspace]} result]
  (let [draft (:draft result)
        slots (:slots (when (:hull draft) (model/slots (:database result) (:hull draft) (:assignments draft))))
        previous (:drawers (when workspace (workspace/workspace! workspace :ships)))
        drawers (workspace-transforms/drawer-states previous slots)]
    (when workspace (workspace/update-workspace! workspace :ships assoc :drawers drawers))
    (htmx/fragment
   ;; Matrices and mount data grow with the assembly and exceed Jetty's
   ;; response-header limit. Hiccup escapes the EDN in this inert body field;
   ;; the viewport consumes it once after HTMX swaps the response into #detail.
     (list (workspace-views/ship-editor "assembly" (views/panel (assoc result :drawers drawers)))
           (workspace-views/ship-editor-library)
           [:input {:type "hidden" :data-assembly-event (pr-str (:event result))}])
     {:status (:status result)})))

(defn current! [deps {:keys [params]}]
  (when (:workspace deps) (workspace/update-workspace! (:workspace deps) :ships update :assembly-filters merge (select-keys params ["bundle" "class"])))
  (let [params (merge (:assembly-filters (when (:workspace deps) (workspace/workspace! (:workspace deps) :ships))) params)]
    (response deps (assoc (db/request! (assoc deps :paint-profile nil) nil {:resume? (not= "1" (get params "poll"))
                                                                            :retry (get params "retry")})
                          :selected-hull (get params "part-id")
                          :selected-bundle (not-empty (get params "bundle"))
                          :selected-class (not-empty (get params "class"))))))

(defn mutate! [{{state :state} :assembly :as deps} op {:keys [parameters]}]
  (locking state
    (let [{:keys [revision slot part-id bundle class discard-revision] :as form} (:form parameters)
          current-revision (get-in @state [:draft :revision])
          current? (= (parse-long revision) current-revision)]
      ;; A hull request includes the current name field, including an unsaved rename.
      (when (and current? (= op :hull) (contains? form :name)
                 (not= (:name form) (or (get-in @state [:draft :name]) "")))
        (swap! state assoc-in [:draft :name] (:name form)))
      (if (and current? (= op :hull) (loadouts/unsaved? deps)
               (not= discard-revision (str current-revision)))
        (htmx/fragment (workspace-views/ship-editor "assembly"
                                                    (views/discard-confirmation "/assembly/hull" "Discard and start assembly"
                                                                                "/assembly?poll=1" form current-revision false)))
        (do
          (when (and (:workspace deps) (#{:hull :reset} op) current?)
            (workspace/update-workspace! (:workspace deps) :ships dissoc :drawers))
          (response deps (assoc (db/request! (assoc deps :paint-profile nil) (cond-> {:op op :revision (parse-long revision) :part-id part-id}
                                                                               slot (assoc :slot (edn/read-string slot))) {})
                                :selected-bundle (not-empty bundle)
                                :selected-class (not-empty class))))))))

(defn save! [deps {:keys [parameters]}]
  (let [{:keys [revision name]} (:form parameters)
        result (loadouts/save! deps (parse-long revision) name)]
    (response deps (merge (db/request! (assoc deps :paint-profile nil) nil {})
                          (if (:error result)
                            {:error (:error result) :status 422}
                            {:saved? true})))))

(defn drawer! [{:keys [workspace assembly] :as deps} {:keys [parameters]}]
  (let [{:keys [slot open revision]} (:form parameters)]
    (when (= (parse-long revision) (get-in @(:state assembly) [:draft :revision]))
      (workspace/update-workspace! workspace :ships assoc-in [:drawers (edn/read-string slot) :open] (= open "true")))
    ;; Disclosure changes HTML only; an incremental envelope retains the scene.
    (current! deps {:params {"poll" "1"}})))

(defn scheme! [{:keys [schemes] {state :state} :assembly :as deps} {:keys [parameters]}]
  (locking state
    (let [{:keys [revision id name]} (:form parameters)
          result (loadout-model/choose-scheme (:draft @state) (parse-long revision)
                                              (when (seq id) (parse-uuid id))
                                              (schemes/listing! schemes))]
      (when-not (:error result)
        (swap! state assoc :draft (cond-> (:draft result) (some? name) (assoc :name name))))
      (response deps (merge (db/request! (assoc deps :paint-profile nil) nil {})
                            (when (:error result) {:error (:error result) :status 422}))))))
