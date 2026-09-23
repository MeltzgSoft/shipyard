(ns shipyard.regions.views
  (:require [clojure.string :as str]
            [shipyard.regions.model :as model]
            [shipyard.workspace.views :as workspace]))

(def attrs (merge workspace/transition-attrs {:hx-post "/parts/regions" :hx-target "#part-regions" :hx-swap "outerHTML"
                                              :hx-disabled-elt "#part-regions input, #part-regions select, #part-regions button, #workspace-navigation button, #library button"}))
(defn fields [part-id mesh-key regions]
  (for [[k v] {"part-id" part-id "mesh-key" mesh-key "revision" (:revision regions)}]
    [:input {:type "hidden" :name k :value v}]))

(defn panel
  ([part-id mesh-key saved selected error] (panel part-id mesh-key saved selected error []))
  ([part-id mesh-key saved selected error shared-layers]
   (let [regions (or saved (model/empty-regions mesh-key))
         available (into model/builtins (sort (remove (set model/builtins) (set (concat shared-layers (:layers regions))))))
         preview (assoc regions :layers available)
         selected (if (some #{selected} available) selected "Secondary")
         stale? (not= mesh-key (:mesh-key regions))]
     [:section#part-regions {:data-regions (pr-str preview) :data-mesh-key mesh-key :data-part-id part-id}
      [:h3 "Paint regions"]
      [:p "Assign faces once; schemes supply the colors. Unassigned faces use Primary. Names created on any library part are available here. Rename and delete affect this part."]
      (when error [:p.detail__error {:role "alert"} error])
      (if stale?
        [:p.detail__error {:role "alert"} "Source mesh changed. Saved regions are retained but not displayed. Reset them to paint this source."]
        (list
         [:form#region-stroke (assoc attrs :hx-sync "this:drop")
          (fields part-id mesh-key regions)
          [:input {:type "hidden" :name "action" :value "assign"}]
          [:input {:type "hidden" :name "faces" :value "[]"}]
          [:label [:input {:type "checkbox" :name "enabled"}] "Paint regions in viewport"]
          [:label "Assign layer" [:select {:name "layer"}
                                  (for [layer available] [:option {:value layer :selected (= selected layer)} layer])]]
          [:label "Region brush radius (screen pixels)" [:input {:type "range" :name "radius" :min 2 :max 100 :value 20}]]
          [:p "Left-drag assigns the selected layer. Right-drag restores Primary. Whole visible triangles only; Alt+drag to orbit."]
          [:p#region-status {:role "status"} "Release to save regions."]]
         [:div.region-legend
          (for [[layer {:keys [base]}] (model/preview-materials preview)]
            [:p [:span.paint-swatch {:style (str "background:rgb(" (str/join "," (map #(* 255 %) base)) ")")}] layer])]
         [:form attrs
          (fields part-id mesh-key regions)
          [:input {:type "hidden" :name "action" :value "add"}]
          [:label "New detail layer" [:input {:name "name" :required true :maxlength 200}]]
          [:button {:type "submit"} "Add detail layer"]]
         (for [layer (drop 2 (:layers regions))]
           [:details [:summary (str "Manage " layer)]
            [:form attrs
             (fields part-id mesh-key regions)
             [:input {:type "hidden" :name "layer" :value layer}]
             [:label "Layer name" [:input {:name "name" :value layer :required true :maxlength 200}]]
             [:button {:name "action" :value "rename" :type "submit"} "Rename layer"]
             [:button {:name "action" :value "delete" :type "submit" :formnovalidate true
                       :onclick "return window.confirm('Delete this layer from this part? Its faces return to Primary. Scheme colors remain available.')"} "Delete layer"]]])))
      [:form (assoc attrs :hx-confirm "Reset all region assignments on this part? All faces will use Primary.")
       (fields part-id mesh-key regions)
       [:button {:name "action" :value "reset" :type "submit"} "Reset regions"]]])))
