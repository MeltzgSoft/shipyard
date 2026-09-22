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
            [shipyard.workspace.db :as workspace]
            [shipyard.workspace.transforms :as transforms]
            [shipyard.workspace.views :as workspace-views]))

(defn- append [response node]
  (update response :body str (:body (htmx/fragment node))))

(defn- ship-preview! [{:keys [workspace preview] :as deps} {:keys [params]}]
  (workspace/remember! workspace :ships params)
  (let [filters (:filters (workspace/workspace! workspace :ships))
        draft (:draft @(:state preview))
        ;; Revalidate the durable selection before refreshing a preview. A failed
        ;; refresh keeps the last usable scene and inspector, without emitting reset.
        checked (when (and (:loadout-id draft) (not (get params "error"))) (loadouts/transfer! deps (:loadout-id draft) :preview))
        result (when-not (or (:error checked) (get params "error"))
                 (assembly/request! (assoc deps :assembly preview) nil
                                    {:resume? (not= "1" (get params "poll")) :retry (get params "retry")}))
        current (:draft @(:state preview))
        error (or (get params "error") (when (:error checked) (get errors/messages (:error checked) "This ship changed. Restore its parts and try again.")))]
    (htmx/fragment
     (list (ship-views/cards (loadouts/list! deps {}) filters)
           (ship-views/inspector (or (:database result) ((:database deps))) current (:prepared result) error)
           (when result [:input {:type "hidden" :data-assembly-event (pr-str (:event result))}])))))

(defn ships! [{:keys [workspace] :as deps} {:keys [headers params] :as request}]
  (if (= "ship-results" (get headers "hx-target"))
    (do
      (workspace/remember! workspace :ships params)
      (htmx/fragment (ship-views/results (loadouts/list! deps {})
                                         (:filters (workspace/workspace! workspace :ships)))))
    (ship-preview! deps request)))

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
        (append (workspace-views/colors-toggle colors))
        (update :headers assoc "HX-Trigger"
                (json/write-str
                 (into (array-map "shipyard:workspace" (pr-str {:mode workspace :activation activation :colors colors}))
                       (when-let [events (get-in response [:headers "HX-Trigger"])] (json/read-str events)))
                 :escape-slash false)))))

(defn colors! [{:keys [workspace]} _]
  (let [{mode :workspace :as context} (workspace/active-context! workspace)]
    (workspace/update-workspace! workspace mode update :colors not)
    (let [colors (:colors (workspace/workspace! workspace mode))]
      (htmx/fragment (list (update (workspace-views/colors-toggle colors) 1 dissoc :hx-swap-oob)
                           (workspace-views/context context colors))
                     {:events {:display {:colors colors}}}))))

(defn transfer! [{{state :state} :assembly :as deps} mode {:keys [parameters]}]
  (locking state
    (let [{:keys [id discard-revision] :as form} (:form parameters)
          revision (get-in @state [:draft :revision])]
      (if (and (not= mode :preview) (loadouts/unsaved? deps)
               (not= discard-revision (str revision)))
        (htmx/fragment
         (assembly-views/discard-confirmation
          (str "/ships/" (name mode)) (str "Discard and " (name mode)) "/ships?poll=1" form revision true))
        (let [result (loadouts/transfer! deps (parse-uuid id) mode)]
          (if (:error result)
            (assoc (ships! deps {:params {"error" (get errors/messages (:error result) "This ship changed. Restore its parts and try again.")}}) :status 422)
            (if (= mode :preview)
              (ships! deps {:params {}})
              (transition! deps {:path-params {:mode "assembly"} :params {} :headers {"hx-request" "true"}}))))))))

(defn transition! [{:keys [workspace library part-handler facets] :as deps} {:keys [path-params params headers]}]
  (workspace/outgoing! deps params)
  (let [mode (keyword (:mode path-params))
        context (if (and (= "1" (get params "resume"))
                         (= mode (:workspace (workspace/active-context! workspace))))
                  (workspace/active-context! workspace)
                  (workspace/activate! workspace mode))]
    (when (and (= mode :orient) (= "1" (get params "table")))
      (workspace/update-workspace! workspace mode assoc :grid? false))
    (binding [workspace/*context* context]
      (let [{:keys [filters selection colors grid?]} (workspace/workspace! workspace mode)]
        (if (not= "true" (get headers "hx-request"))
          (htmx/page (views/shell (facets) (index/root! library) context colors))
          (transition-response
           workspace
           (cond->
            (case mode
              :assembly (assembly-handlers/current! deps {:params (merge filters (select-keys params ["part-id"]))})
              :ships (ships! deps {:params filters})
              :paint (paint/current! deps {:params (merge filters (select-keys params ["error"]))})
              :browse (append (if selection (part-handler {:params {} :path-params {:id selection}})
                                  (htmx/fragment (views/detail-empty) {:events {:clear nil}}))
                              [:section#library.panel {:hx-swap-oob "outerHTML"}
                               [:h2.panel__title "Part Browser"] (views/settings-panel (index/root! library))
                               (transforms/selected-filters (views/filter-form (facets)) filters)
                               [:div#library-results]])
              :orient (let [grid (when (and grid? (seq selection)) (orient/render! deps {:params {"part-ids" selection}}))
                            panel (transforms/selected-filters (orient-views/panel (facets) selection) filters)]
                        (-> (htmx/fragment (views/detail-empty)
                                           (when-not grid {:events {:clear nil}}))
                            (append (into [(first panel) {:hx-swap-oob "outerHTML"}] (rest panel)))
                            (append [:section#bulk-orient.bulk-orient__stage {:hx-swap-oob "innerHTML"}
                                     (when grid (html/raw (:body grid)))]))))
             (not= mode :orient) (append [:section#bulk-orient.bulk-orient__stage {:hx-swap-oob "innerHTML"}]))))))))

(defn render-orient! [{:keys [workspace] :as deps} {:keys [params] :as request}]
  (if (or (nil? workspace) (= "1" (get params "poll")))
    (orient/render! deps request)
    (let [response (orient/render! deps request)]
      (if (= 200 (:status response))
        (transition! deps {:path-params {:mode "orient"} :params params :headers {"hx-request" "true"}})
        response))))

(defn paint-selection! [{:keys [workspace] :as deps} action {:keys [params]}]
  (let [result (case action
                 :create (paint/create! deps params)
                 :rename (paint/rename! deps params)
                 :default (paint/default! deps params)
                 :group-create (paint/group! deps :create params)
                 :group-rename (paint/group! deps :rename params)
                 :group-delete (paint/group! deps :delete params)
                 :group-members (paint/group! deps :members params)
                 :group-order (paint/group! deps :order params)
                 (paint/select! deps params))]
    (workspace/update-workspace! workspace :paint assoc :edit-sequence 0)
    (transition! deps {:path-params {:mode "paint"} :params (when (:error result) {"error" (:error result)})
                       :headers {"hx-request" "true"}})))

(defn paint-transfer! [{:keys [assembly preview workspace] :as deps} source {:keys [params]}]
  (workspace/outgoing! deps params)
  (let [result (paint-db/transfer! deps (if (= source :assembly) assembly preview))]
    (if (:error result)
      (append (if (= source :assembly)
                (assembly-handlers/current! deps {:params {"poll" "1"}})
                (ships! deps {:params {"poll" "1"}}))
              [:p.detail__error {:role "alert"} "Select a valid assembled ship before painting. Restore missing parts and retry."])
      (do
        (workspace/update-workspace! workspace :paint dissoc :target :edit-sequence)
        (transition! deps {:path-params {:mode "paint"} :params {} :headers {"hx-request" "true"}})))))
