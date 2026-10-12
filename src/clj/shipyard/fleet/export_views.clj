(ns shipyard.fleet.export-views
  (:require [shipyard.workspace.views :as workspace]))

(defn controls [record destination ready? result]
  [:section#fleet-export-controls
   [:h3 "Export print bundle"]
   [:p (if destination (str "Export directory: " destination) "Choose an export directory in Settings.")]
   [:form (merge workspace/transition-attrs
                 {:method "post" :action "/fleets/export" :hx-post "/fleets/export" :hx-target "#detail"
                  :hx-disabled-elt "find button, [data-workspace-transition], [data-workspace-mode]"})
    [:input {:type "hidden" :name "id" :value (str (:fleet/id record))}]
    [:button {:type "submit" :disabled (not (and destination ready?))} "Export ZIP"]]
   (when-let [path (:path result)] [:p {:role "status" :data-fleet-export-path path} (str "Saved print bundle: " path)])])
