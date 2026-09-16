(ns shipyard.assembly.handlers
  "Thin Ring orchestration; transport validation is in routes."
  (:require [clojure.edn :as edn]
            [shipyard.assembly.db :as db]
            [shipyard.assembly.views :as views]
            [shipyard.assembly.model :as model]
            [shipyard.workspace.transforms :as workspace-transforms]
            [shipyard.http.htmx :as htmx]
            [shipyard.loadout.operations :as loadouts]
            [shipyard.workspace.db :as workspace]))

(defn- response [{:keys [workspace]} result]
  (let [draft (:draft result)
        slots (:slots (when (:hull draft) (model/slots (:database result) (:hull draft) (:assignments draft))))
        previous (:drawers (when workspace (workspace/workspace! workspace :assembly)))
        drawers (workspace-transforms/drawer-states previous slots)]
    (when workspace (workspace/update-workspace! workspace :assembly assoc :drawers drawers))
    (htmx/fragment
   ;; Matrices and mount data grow with the assembly and exceed Jetty's
   ;; response-header limit. Hiccup escapes the EDN in this inert body field;
   ;; the viewport consumes it once after HTMX swaps the response into #detail.
     (list (views/panel (assoc result :drawers drawers))
           [:input {:type "hidden" :data-assembly-event (pr-str (:event result))}])
     {:status (:status result)})))

(defn current! [deps {:keys [params]}]
  (when (:workspace deps) (workspace/remember! (:workspace deps) :assembly params))
  (let [params (merge (:filters (when (:workspace deps) (workspace/workspace! (:workspace deps) :assembly))) params)]
    (response deps (assoc (db/request! deps nil {:resume? (not= "1" (get params "poll"))
                                                 :retry (get params "retry")})
                          :selected-hull (get params "part-id")
                          :selected-bundle (not-empty (get params "bundle"))
                          :selected-class (not-empty (get params "class"))))))

(defn mutate! [deps op {:keys [parameters]}]
  (let [{:keys [revision slot part-id bundle class]} (:form parameters)]
    (when (and (:workspace deps) (#{:hull :reset} op)
               (= (parse-long revision) (get-in @(:state (:assembly deps)) [:draft :revision])))
      (workspace/update-workspace! (:workspace deps) :assembly dissoc :drawers))
    (response deps (assoc (db/request! deps (cond-> {:op op :revision (parse-long revision) :part-id part-id}
                                              slot (assoc :slot (edn/read-string slot))) {})
                          :selected-bundle (not-empty bundle)
                          :selected-class (not-empty class)))))

(defn save! [deps {:keys [parameters]}]
  (let [{:keys [revision name]} (:form parameters)
        result (loadouts/save! deps (parse-long revision) name)]
    (response deps (merge (db/request! deps nil {})
                          (if (:error result)
                            {:error (:error result) :status 422}
                            {:saved? true})))))

(defn drawer! [{:keys [workspace assembly] :as deps} {:keys [parameters]}]
  (let [{:keys [slot open revision]} (:form parameters)]
    (when (= (parse-long revision) (get-in @(:state assembly) [:draft :revision]))
      (workspace/update-workspace! workspace :assembly assoc-in [:drawers (edn/read-string slot) :open] (= open "true")))
    (current! deps {:params {}})))
