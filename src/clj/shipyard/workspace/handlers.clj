(ns shipyard.workspace.handlers
  "One workspace transition resolves the destination's own server state."
  (:require [clojure.data.json :as json]
            [hiccup2.core :as html]
            [shipyard.assembly.db :as assembly]
            [shipyard.assembly.handlers :as assembly-handlers]
            [shipyard.assembly.responses :as errors]
            [shipyard.assembly.views :as assembly-views]
            [shipyard.bulk-orientation.handlers :as orient]
            [shipyard.bulk-orientation.views :as orient-views]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.views :as views]
            [shipyard.library.index :as index]
            [shipyard.loadout.operations :as loadouts]
            [shipyard.loadout.views :as ship-views]
            [shipyard.paint.db :as paint-db]
            [shipyard.paint.handlers :as paint]
            [shipyard.scheme.editor :as scheme-editor]
            [shipyard.scheme.material :as material]
            [shipyard.catalog.db :as catalog]
            [shipyard.ship.db :as named-ships]
            [shipyard.workspace.db :as workspace]
            [shipyard.workspace.transforms :as transforms]
            [shipyard.workspace.views :as workspace-views]))

(defn- append [response node]
  (update response :body str (:body (htmx/fragment node))))

(defn- class-entries! [{:keys [named-ships] :as deps}]
  (let [ships (group-by :ship/class (vals (named-ships/listing! named-ships)))]
    (mapv #(assoc % :ships (get ships (get-in % [:loadout :loadout/id]) []))
          (loadouts/list! deps {}))))

(defn- ship-preview! [{:keys [workspace assembly] :as deps} {:keys [params]}]
  (let [tab (or (:inspector-tab (workspace/workspace! workspace :ships)) "assembly")]
    (if (= tab "assembly")
      (assembly-handlers/current! deps {:params params})
      (let [result (when (= tab "schemes")
                     (assembly/request! (assoc deps :paint-profile (scheme-editor/selected! deps)) nil
                                        {:resume? (not= "1" (get params "poll")) :retry (get params "retry")}))
            error (get params "error")]
        (htmx/fragment
         (list (workspace-views/ship-editor-library)
               (workspace-views/ship-editor
                tab
                (if (= tab "paint")
                  (html/raw (:body (paint/current! deps {:params params})))
                  (list (scheme-editor/panel deps (:database result) (:draft @(:state assembly)) error)
                        (when (some #(= :running (:state %)) (vals (:prepared result)))
                          [:span {:hx-get "/ships?poll=1" :hx-trigger "load delay:400ms" :hx-target "#detail" :hx-sync "#detail:abort"}]))))
               (when result [:input {:type "hidden" :data-assembly-event (pr-str (:event result))}])))))))

(defn ships! [{:keys [workspace] :as deps} {:keys [headers params] :as request}]
  (let [table? (not= :editor (:view (workspace/workspace! workspace :ships)))]
    (cond
      (= "ship-results" (get headers "hx-target"))
      (do (workspace/remember! workspace :ships params)
          (htmx/fragment (ship-views/results (class-entries! deps) (:filters (workspace/workspace! workspace :ships)))))
      table?
      (do (workspace/remember! workspace :ships params)
          (htmx/fragment (cond-> (ship-views/cards (class-entries! deps) (:filters (workspace/workspace! workspace :ships)))
                           (get params "error") (conj [:p.detail__error {:role "alert"} (get params "error")]))))
      :else (ship-preview! deps request))))

(defn named-rows! [deps {:keys [parameters params]}]
  (let [id (parse-uuid (get-in parameters [:path :id]))
        entry (some #(when (= id (get-in % [:loadout :loadout/id])) %) (class-entries! deps))]
    (if entry (htmx/fragment (ship-views/named-rows entry (get params "page") (get params "q")))
        (htmx/fragment [:p.detail__error "This ship class is unavailable."] {:status 404}))))

(defn delete-ship! [deps {:keys [parameters]}]
  (let [id (parse-uuid (get-in parameters [:form :id]))
        result (loadouts/delete! deps id)]
    (if (:error result)
      (assoc (ships! deps {:params {"error" (or (:message result) (get errors/messages (:error result)))}})
             :status 422)
      (ships! deps {:params {"poll" "1"}}))))

(declare transition!)

(defn- transition-response [db response]
  (let [{:keys [workspace activation] :as context} (workspace/active-context! db)
        colors (:colors (workspace/workspace! db workspace))]
    (-> response
        (assoc :status 200)
        (append (workspace-views/context context colors))
        (append (workspace-views/navigation workspace))
        (append (workspace-views/colors-toggle colors workspace))
        (update :headers assoc "HX-Trigger"
                (json/write-str
                 (into (array-map "shipyard:workspace" (pr-str {:mode workspace :activation activation :colors colors}))
                       (when-let [events (get-in response [:headers "HX-Trigger"])] (json/read-str events)))
                 :escape-slash false)))))

(defn colors! [{:keys [workspace]} _]
  (let [{mode :workspace :as context} (workspace/active-context! workspace)]
    (workspace/update-workspace! workspace mode update :colors not)
    (let [colors (:colors (workspace/workspace! workspace mode))]
      (htmx/fragment (list (update (workspace-views/colors-toggle colors mode) 1 dissoc :hx-swap-oob)
                           (workspace-views/context context colors))
                     {:events {:display {:colors colors}}}))))

(defn new-ship! [{{state :state} :assembly :keys [workspace] :as deps} {:keys [parameters params]}]
  (workspace/remember! workspace :ships params)
  (locking state
    (let [revision (get-in @state [:draft :revision])]
      (if (and (loadouts/unsaved? deps) (not= (get-in parameters [:form :discard-revision]) (str revision)))
        (htmx/fragment (list (workspace-views/ship-editor-library)
                             (workspace-views/ship-editor "assembly"
                                                          (assembly-views/discard-confirmation "/ships/new" "Discard and create class"
                                                                                               "/workspace/ships?table=1" {} revision true))))
        (do
          (assembly/request! deps {:op :reset :revision revision} {})
          (workspace/update-workspace! workspace :ships dissoc :drawers)
          (workspace/update-workspace! workspace :ships assoc :needs-scene-reset? true)
          (transition! deps {:path-params {:mode "assembly"} :params {} :headers {"hx-request" "true"}}))))))

(defn open-ship! [{{state :state} :assembly :keys [workspace named-ships] :as deps} {:keys [parameters params]}]
  (workspace/remember! workspace :ships params)
  (locking state
    (let [{:keys [id kind discard-revision]} (:form parameters)
          ship (when (= kind "ship") (named-ships/record! named-ships (parse-uuid id)))
          class-id (if (= kind "ship") (:ship/class ship) (parse-uuid id))
          draft (:draft @state)
          same? (= class-id (:loadout-id draft))
          resume? (and same? (loadouts/unsaved? deps))]
      (if (and (loadouts/unsaved? deps) (not same?) (not= discard-revision (str (:revision draft))))
        (htmx/fragment (list (workspace-views/ship-editor-library)
                             (workspace-views/ship-editor "assembly"
                                                          (assembly-views/discard-confirmation "/ships/open" "Discard and open" "/workspace/ships?table=1"
                                                                                               (:form parameters) (:revision draft) true))))
        (let [result (if (and (= kind "ship") (nil? ship)) {:error :missing-loadout}
                         (when-not resume? (loadouts/transfer! deps class-id :edit)))]
          (if (:error result)
            (ships! deps {:params {"error" (get errors/messages (:error result) "This ship class is unavailable.")}})
            (do
              (when ship (paint/select! deps {"id" id}))
              (when-not ship
                (paint-db/transfer! deps (:assembly deps) (:scheme (workspace/workspace! workspace :ships))))
              (workspace/update-workspace! workspace :ships dissoc :target :edit-sequence :brush-sequence :brush-history)
              (workspace/update-workspace! workspace :ships assoc :view :editor :inspector-tab (if ship "paint" "assembly"))
              (transition! deps {:path-params {:mode "ships"} :params {} :headers {"hx-request" "true"}}))))))))

(defn transfer! [{{state :state} :assembly :as deps} mode {:keys [parameters] :as request}]
  (if (not= mode :duplicate)
    (open-ship! deps (assoc-in request [:parameters :form :kind] "class"))
    (locking state
      (let [{:keys [id discard-revision] :as form} (:form parameters)
            revision (get-in @state [:draft :revision])]
        (if (and (loadouts/unsaved? deps) (not= discard-revision (str revision)))
          (htmx/fragment (list (workspace-views/ship-editor-library)
                               (workspace-views/ship-editor "assembly"
                                                            (assembly-views/discard-confirmation "/ships/duplicate" "Discard and duplicate"
                                                                                                 "/workspace/ships?table=1" form revision true))))
          (let [result (loadouts/transfer! deps (parse-uuid id) :duplicate)]
            (if (:error result)
              (ships! deps {:params {"error" (get errors/messages (:error result))}})
              (transition! deps {:path-params {:mode "assembly"} :params {} :headers {"hx-request" "true"}}))))))))

(defn transition! [{:keys [workspace library part-handler facets] :as deps} {:keys [path-params params headers]}]
  (workspace/outgoing! deps params)
  (let [mode (case (:mode path-params) "orient" :browse "assembly" :ships (keyword (:mode path-params)))
        same-ships? (and (= mode :ships) (= :ships (:workspace (workspace/active-context! workspace))) (not= "1" (get params "resume")))
        context (if (and (= "1" (get params "resume"))
                         (= mode (:workspace (workspace/active-context! workspace))))
                  (workspace/active-context! workspace)
                  (workspace/activate! workspace mode))]
    (when (and (= mode :ships) (not same-ships?))
      (workspace/update-workspace! workspace :ships assoc :needs-scene-reset? true))
    (when (and (= mode :ships) (= "assembly" (:mode path-params)))
      (workspace/update-workspace! workspace :ships assoc :view :editor :inspector-tab "assembly"))
    (when (and (= mode :ships) (= "1" (get params "table")))
      (workspace/update-workspace! workspace :ships assoc :view :table))
    (when (and (= mode :browse) (= "1" (get params "table")))
      (workspace/update-workspace! workspace mode assoc :view :table))
    (when (and (= mode :browse) (get params "part-id"))
      (workspace/update-workspace! workspace :browse assoc :view :part :selection (get params "part-id")))
    (workspace/update-workspace! workspace :ships dissoc :brush-pending)
    (binding [workspace/*context* context]
      (let [{:keys [filters selection colors bulk-selection view]} (workspace/workspace! workspace mode)]
        (if (not= "true" (get headers "hx-request"))
          (htmx/page (views/shell (facets) (index/root! library) context colors))
          (transition-response
           workspace
           (cond->
            (case mode
              :ships (ships! deps {:params (cond-> (select-keys params ["error" "part-id"]) same-ships? (assoc "poll" "1"))})
              :browse
              (if (= view :part)
                (-> (if selection (part-handler {:params {} :path-params {:id selection}})
                        (htmx/fragment (views/detail-empty) {:events {:clear nil}}))
                    (append [:section#library.panel {:hx-swap-oob "outerHTML" :data-part-view "part"}])
                    (append [:section#bulk-orient.bulk-orient__stage {:hx-swap-oob "innerHTML"}]))
                (let [grid (when (= view :grid) (orient/render! deps {:params {"part-ids" bulk-selection}}))
                      panel (transforms/selected-filters (orient-views/panel (facets) bulk-selection (index/root! library)) filters)]
                  (-> (htmx/fragment (views/detail-empty) (when-not grid {:events {:clear nil}}))
                      (append (into [(first panel) {:hx-swap-oob "outerHTML"}] (rest panel)))
                      (append [:section#bulk-orient.bulk-orient__stage {:hx-swap-oob "innerHTML"}
                               (when grid (html/raw (:body grid)))])))))
             (not= mode :browse) (append [:section#bulk-orient.bulk-orient__stage {:hx-swap-oob "innerHTML"}]))))))))

(defn render-orient! [{:keys [workspace] :as deps} {:keys [params] :as request}]
  (if (or (nil? workspace) (= "1" (get params "poll")))
    (orient/render! deps request)
    (let [response (orient/render! deps request)]
      (if (= 200 (:status response))
        (transition! deps {:path-params {:mode "browse"} :params params :headers {"hx-request" "true"}})
        (append response [:div#part-edit-status {:hx-swap-oob "outerHTML" :role "alert"}
                          (html/raw (:body response))])))))

(defn paint-selection! [{:keys [workspace] :as deps} action {:keys [params]}]
  (if (and (= action :target) (= (get params "target") (:target (workspace/workspace! workspace :ships))))
    (htmx/fragment nil {:status 204})
    (let [result (case action
                   :create (paint/create! deps params)
                   :rename (paint/rename! deps params)
                   :delete (paint/delete! deps params)
                   :default (paint/default! deps params)
                   :reset (paint/reset! deps params)
                   :tool (when (#{"select" "brush"} (get params "tool"))
                           (workspace/update-workspace! workspace :ships assoc :tool (get params "tool")))
                   :group-create (paint/group! deps :create params)
                   :group-rename (paint/group! deps :rename params)
                   :group-delete (paint/group! deps :delete params)
                   :group-members (paint/group! deps :members params)
                   :group-order (paint/group! deps :order params)
                   (paint/select! deps params))]
      (when (not= action :tool)
        (workspace/update-workspace! workspace :ships assoc :edit-sequence 0 :brush-sequence 0 :brush-history nil))
      (workspace/update-workspace! workspace :ships assoc :view :editor :inspector-tab "paint")
      (ship-preview! deps {:params (cond-> {"poll" "1"} (:error result) (assoc "error" (:error result)))}))))

(defn paint-transfer! [{:keys [assembly preview workspace] :as deps} source {:keys [params]}]
  (workspace/outgoing! deps params)
  (let [result (if (and (= source :assembly) (loadouts/unsaved? deps))
                 {:error :unsaved-class}
                 (paint-db/transfer! deps (if (= source :assembly) assembly preview)
                                     (:scheme (workspace/workspace! workspace :ships))))]
    (if (:error result)
      (append (if (= source :assembly)
                (assembly-handlers/current! deps {:params {"poll" "1"}})
                (ships! deps {:params {"poll" "1"}}))
              [:p.detail__error {:role "alert"} "Save a ship class before creating a named ship. Restore missing parts and retry."])
      (do
        (workspace/update-workspace! workspace :ships dissoc :target :edit-sequence :brush-sequence :brush-history)
        (workspace/update-workspace! workspace :ships assoc :view :editor :inspector-tab "paint")
        (transition! deps {:path-params {:mode "ships"} :params {} :headers {"hx-request" "true"}})))))

(defn scheme-change! [{:keys [catalog assembly] :as deps} action {:keys [params]}]
  (let [result (scheme-editor/change! deps action params)]
    (if (#{:layer :material} action)
      (let [palette (scheme-editor/selected! deps)
            layers (:scheme/layers palette)
            base (or (get layers "Primary") material/neutral)]
        ;; Palette edits change no geometry, class assignment or face masks.
        ;; Keep both scene projections current without reconstructing the ship.
        (when (= action :material)
          (swap! (:state assembly) update :scene
                 (fn [scene] (into {} (map (fn [[path placement]]
                                             [path (assoc placement :layers layers :material base)])) scene))))
        (htmx/fragment
         (list (scheme-editor/panel deps {:registry (catalog/region-registry! catalog)}
                                    (:draft @(:state assembly)) (:error result))
               (when (= action :material)
                 [:input {:type "hidden" :data-scheme-palette
                          (pr-str (merge workspace/*context* {:layers layers}))}]))))
      (ships! deps {:params (cond-> {"poll" "1" "tab" "schemes"} (:error result) (assoc "error" (:error result)))}))))

(defn ship-tab! [{:keys [workspace assembly preview] :as deps} {:keys [path-params params]}]
  (workspace/outgoing! deps params)
  (let [tab (if (= "class" (:tab path-params)) "assembly" (:tab path-params))
        draft (:draft @(:state assembly))
        paint-class (get-in @(:state preview) [:draft :class-id])
        result (when (and (= tab "paint") (not (loadouts/unsaved? deps)) (or (not= (:loadout-id draft) paint-class) (nil? (get-in @(:state preview) [:draft :ship-id]))))
                 (paint-db/transfer! deps assembly (:scheme (workspace/workspace! workspace :ships))))]
    (if (and (= tab "paint") (or (loadouts/unsaved? deps) (:error result) (nil? (:loadout-id draft))))
      (append (ship-preview! deps {:params {"poll" "1"}})
              [:p.detail__error {:role "alert"} "Save this ship class in Assembly before painting a named ship."])
      (do
        (workspace/update-workspace! workspace :ships assoc :view :editor :inspector-tab tab)
        (transition! deps {:path-params {:mode "ships"} :params {} :headers {"hx-request" "true"}})))))
