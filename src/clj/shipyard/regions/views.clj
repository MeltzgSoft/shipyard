(ns shipyard.regions.views
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [shipyard.regions.model :as model]
            [shipyard.regions.migration :as migration]
            [shipyard.regions.registry :as registry]
            [shipyard.workspace.views :as workspace]))

(def attrs (merge workspace/transition-attrs {:method "post" :action "/parts/regions" :hx-post "/parts/regions" :hx-target "#part-regions" :hx-swap "outerHTML"
                                              :hx-include "#region-stroke input[name=mode], #region-stroke input[name=angle]"
                                              :hx-disabled-elt "#part-regions input, #part-regions select, #part-regions button, #workspace-navigation button, #library button"}))
(defn fields [part-id mesh-key regions]
  (for [[k v] {"part-id" part-id "mesh-key" mesh-key "revision" (:revision regions)
               "layer-revision" (:layer-revision regions)}]
    [:input {:type "hidden" :name k :value v}]))

(defn- pencil-icon []
  [:svg {:viewBox "0 0 24 24" :width 15 :height 15 :fill "none" :stroke "currentColor"
         :stroke-width 1.7 :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
   [:path {:d "m16 3 5 5M4 15 16 3a2 2 0 0 1 5 5L9 20l-6 1z"}]])

(defn- layer-list [part-id mesh-key regions available selected stale?]
  (let [palette (model/preview-materials regions)]
    [:ul.region-layers {:aria-label "Paint layers"}
     (for [layer available
           :let [detail? (not (some #{layer} model/builtins))
                 label (get-in regions [:layer-definitions layer :name] layer)
                 base (get-in palette [layer :base])]]
       [:li.region-layer
        [:div.region-layer__row
         [:button.region-layer__select {:type "button" :data-region-layer layer
                                        :aria-label (str "Paint " label) :aria-pressed (str (= selected layer)) :disabled stale?}
          [:span.paint-swatch {:aria-hidden "true" :style (str "background:rgb(" (str/join "," (map #(* 255 %) base)) ")")}]
          [:span.region-layer__name label]]
         (when detail?
           [:div.region-layer__actions
            [:button.region-layer__edit
             {:type "button" :aria-label (str "Rename " label)
              :title "Rename shared layer"
              :hx-on:click "var form=this.closest('.region-layer').querySelector('.region-layer__rename'); form.hidden=!form.hidden; this.setAttribute('aria-expanded', !form.hidden); if(!form.hidden){var input=form.querySelector('input[name=name]'); input.focus(); input.select();}"
              :aria-expanded "false"}
             (pencil-icon)]
            [:form.region-layer__delete
             (assoc attrs :hx-confirm (str "Delete layer “" label "” from every part in this library? Its painted regions will return to Primary."))
             (fields part-id mesh-key regions)
             [:input {:type "hidden" :name "layer" :value layer}]
             [:input {:type "hidden" :name "action" :value "delete"}]
             [:input {:type "hidden" :name "confirmed" :value "true"}]
             [:button {:type "submit" :aria-label (str "Delete " label) :title "Delete from every part"}
              [:span {:aria-hidden "true"} "×"]]]])]
        (when detail?
          [:form.region-layer__rename (assoc attrs :hidden true)
           (fields part-id mesh-key regions)
           [:input {:type "hidden" :name "layer" :value layer}]
           [:label "Shared layer name" [:input {:name "name" :value label :required true :maxlength 200}]]
           [:div.region-layer__rename-actions
            [:button {:name "action" :value "rename" :type "submit"} "Save name"]
            [:button {:type "button"
                      :hx-on:click "this.closest('form').reset(); this.closest('form').hidden=true; this.closest('.region-layer').querySelector('.region-layer__edit').setAttribute('aria-expanded', false); this.closest('.region-layer').querySelector('.region-layer__edit').focus();"} "Cancel"]]])])]))

(defn panel
  ([part-id mesh-key saved selected error] (panel part-id mesh-key saved selected error registry/empty-registry))
  ([part-id mesh-key saved selected error shared]
   (panel part-id mesh-key saved selected error shared {}))
  ([part-id mesh-key saved selected error shared {:keys [mode angle] :or {mode "facets" angle 1}}]
   (let [available (registry/ids shared)
         regions (assoc (or saved (migration/regions (model/empty-regions mesh-key)))
                        :layer-revision (:revision shared)
                        :layer-definitions (:layers shared))
         preview (assoc regions :layers available)
         selected (if (some #{selected} available) selected "Secondary")
         stale? (not= mesh-key (:mesh-key regions))]
     [:section#part-regions {:data-regions (pr-str (dissoc preview :faces))
                             :data-region-faces (json/write-str (:faces preview))
                             :data-mesh-key mesh-key :data-part-id part-id}
      [:h3 "Paint regions"]
      [:p.muted "Select a layer to paint. Schemes supply the final colors."]
      (when error [:p.detail__error {:role "alert"} error])
      (layer-list part-id mesh-key preview available selected stale?)
      (if stale?
        [:p.detail__error {:role "alert"} "Source mesh changed. Saved regions are retained but not displayed. Reset them to paint this source."]
        (list
         [:form#region-add attrs
          (fields part-id mesh-key regions)
          [:input {:type "hidden" :name "action" :value "add"}]
          [:input {:name "name" :aria-label "New detail layer" :placeholder "New detail layer" :required true :maxlength 200}]
          [:button {:type "submit"} "Add layer"]]
         [:form#region-stroke (assoc attrs :hx-sync "this:drop" :hx-ext "region-cbor"
                                     :action "/parts/regions/stroke" :hx-post "/parts/regions/stroke")
          (fields part-id mesh-key regions)
          [:input {:type "hidden" :name "action" :value "assign"}]
          [:input {:type "hidden" :name "layer" :value selected}]
          [:input {:type "hidden" :name "mode" :value mode}]
          [:div.region-brush-mode {:role "group" :aria-label "Paint by"}
           [:span "Paint by"]
           (for [[value label] [["facets" "Facets"] ["faces" "Faces"]]]
             [:button {:type "button" :data-region-mode value :aria-pressed (str (= mode value))} label])]
          [:p.muted "Facets paints individual triangles; Faces follows connected surfaces."]
          [:div#region-angle-control {:hidden (not= mode "faces")}
           [:label [:span "Angle tolerance " [:output {:for "region-angle"} (str angle "°")]]
            [:input#region-angle {:type "range" :name "angle" :min 0 :max 90 :step 1 :value angle
                                  :hx-on:input "this.closest('label').querySelector('output').value=this.value+'°'"}]]
           [:p.muted "Maximum angle between neighboring triangles. Increase to follow curves; larger creases stop painting."]]
          [:label "Region brush radius (screen pixels)" [:input {:type "range" :name "radius" :min 2 :max 100 :value 20}]]
          [:fieldset.region-mirror
           [:legend "Symmetry"]
           [:label [:input {:type "checkbox" :name "mirror"}] " Mirror painting"]
           [:label "Mirror plane"
            [:select {:name "mirror-axis" :disabled true}
             [:option {:value "x"} "YZ plane (across X)"]
             [:option {:value "y"} "XZ plane (across Y)"]
             [:option {:value "z"} "XY plane (across Z)"]]]
           [:label "Mirror plane offset"
            [:input {:type "number" :name "mirror-offset" :step "any" :placeholder "Model center" :disabled true}]]
           [:p.muted "Planes follow the part axes. Blank offset uses the model center. Paint and erase also affect matching hidden faces."]]
          [:p.muted "Left-drag paints; right-drag erases. Alt+drag orbits."]
          [:p#region-status {:role "status"} "Release to save regions."]]
         [:form#region-fill (assoc attrs :hx-include "#region-stroke input[name=layer], #region-stroke input[name=mode], #region-stroke input[name=angle]")
          (fields part-id mesh-key regions)
          [:input {:type "hidden" :name "action" :value "fill"}]
          [:button {:type "submit"} "Apply layer to entire part"]
          [:p.muted "Replaces every face assignment, including hidden faces, with the selected layer. Primary clears assignments."]]))
      [:form (assoc attrs :hx-confirm "Reset all region assignments on this part? All faces will use Primary.")
       (fields part-id mesh-key regions)
       [:button {:name "action" :value "reset" :type "submit"} "Reset regions"]]])))
