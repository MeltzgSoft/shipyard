(ns shipyard.workspace.handlers
  "One workspace transition resolves the destination's own server state."
  (:require [hiccup2.core :as html]
            [shipyard.assembly.db :as assembly]
            [shipyard.assembly.handlers :as assembly-handlers]
            [shipyard.assembly.responses :as errors]
            [shipyard.bulk-orientation.handlers :as orient]
            [shipyard.bulk-orientation.views :as orient-views]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.views :as views]
            [shipyard.library.index :as index]
            [shipyard.loadout.operations :as loadouts]
            [shipyard.loadout.views :as ship-views]
            [shipyard.workspace.db :as workspace]
            [shipyard.workspace.transforms :as transforms]))

(defn- append [response node]
  (update response :body str (:body (htmx/fragment node))))

(defn- ship-preview! [{:keys [workspace preview] :as deps} {:keys [params]}]
  (workspace/remember! workspace :ships params)
  (let [filters (:filters (workspace/workspace! workspace :ships))
        draft (:draft @(:state preview))
        ;; Revalidate the durable selection before refreshing a preview. A failed
        ;; refresh keeps the last usable scene and inspector, without emitting reset.
        checked (when (:loadout-id draft) (loadouts/transfer! deps (:loadout-id draft) :preview))
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

(defn transfer! [{:keys [workspace] :as deps} mode {:keys [parameters]}]
  (let [id (parse-uuid (get-in parameters [:form :id]))
        result (loadouts/transfer! deps id mode)]
    (if (:error result)
      (assoc (ships! deps {:params {"error" (get errors/messages (:error result) "This ship changed. Restore its parts and try again.")}}) :status 422)
      (if (= mode :preview)
        (ships! deps {:params {}})
        (let [context (assoc workspace/*context* :workspace :assembly
                             :activation (inc (or (:activation workspace/*context*) 0)))
              response (binding [workspace/*context* context]
                         (assembly-handlers/current! deps {:params (:filters (workspace/workspace! workspace :assembly))}))]
          (update response :headers merge {"X-Shipyard-Destination" "assembly"
                                           "X-Shipyard-Colors" (str (:colors (workspace/workspace! workspace :assembly)))}))))))

(defn transition! [{:keys [workspace library part-handler facets] :as deps} {:keys [path-params params]}]
  (workspace/outgoing! deps params)
  (let [mode (keyword (:mode path-params))
        {:keys [filters selection colors grid?]} (workspace/workspace! workspace mode)
        response
        (case mode
          :assembly (assembly-handlers/current! deps {:params (merge filters (select-keys params ["part-id"]))})
          :ships (ships! deps {:params filters})
          :browse (append (if selection (part-handler {:params {} :path-params {:id selection}})
                              (htmx/fragment (views/detail-empty) {:events {:clear nil}}))
                          [:section#library.panel {:hx-swap-oob "outerHTML"}
                           [:h2.panel__title "Part Browser"] (views/settings-panel (index/root! library))
                           (transforms/selected-filters (views/filter-form (facets)) filters)
                           [:div#library-results]])
          :orient (let [grid (when (and grid? (seq selection)) (orient/render! deps {:params {"part-ids" selection}}))
                        panel (transforms/selected-filters (orient-views/panel (facets)) filters)]
                    (-> (htmx/fragment (views/detail-empty))
                        (append (into [(first panel) {:hx-swap-oob "outerHTML"}] (rest panel)))
                        (append [:section#bulk-orient.bulk-orient__stage {:hx-swap-oob "innerHTML"}
                                 [:input {:type "hidden" :data-bulk-restored-selection (or selection "[]")}]
                                 (when grid (html/raw (:body grid)))]))))]
    (update response :headers assoc "X-Shipyard-Colors" (str colors))))
