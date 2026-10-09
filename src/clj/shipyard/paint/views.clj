(ns shipyard.paint.views
  (:require [shipyard.help.views :as help]
            [shipyard.loadout.views :as ship-views]
            [shipyard.scheme.color :as color]
            [shipyard.scheme.presets :as presets]
            [shipyard.workspace.views :as workspace]))

(def selection-attrs
  (merge workspace/transition-attrs {:hx-target "#detail" :hx-swap "innerHTML settle:0ms"
                                     :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition], .ship-inspector button, .ship-inspector input, .ship-inspector select"}))

(def competing-controls
  ".ship-inspector > nav button, .ship-card button, #workspace-navigation button, #paint-scheme select, #paint-reset button, #paint-create button, #paint-rename button, #paint-delete button, .named-ship-actions button, #mount-colors-toggle")

(defn transfer-button [source label]
  [:form (merge selection-attrs {:novalidate true :method "post" :action (str "/" source "/paint") :hx-post (str "/" source "/paint")
                                 :hx-include ".assembly__save input[name=name]"})
   [:button {:type "submit" :data-workspace-transition "true"} label]])

(defn scheme-options [records selected]
  (list [:option {:value ""} "No scheme"]
        (for [r (sort-by :scheme/name records)]
          [:option {:value (str (:scheme/id r)) :selected (= (:scheme/id r) selected)} (:scheme/name r)])))

(defn ship-controls [{:keys [schemes]} draft record]
  [:div.paint-scheme
   [:h2 (if record (:name draft) "New named ship")]
   [:details {:open (nil? record)} [:summary "New named ship"]
    [:form#paint-create (merge selection-attrs {:method "post" :action "/ships/paint/create" :hx-post "/ships/paint/create"})
     [:p (if (:class-id draft) (str (:class-name draft) " class") "Select a ship class first.")]
     [:input {:type "hidden" :name "class" :value (str (:class-id draft))}]
     [:label "Ship name" [:input {:name "name" :required true :maxlength 200}]]
     [:label "Fleet scheme" [:select (merge
                                      (help/attrs "Change this ship’s fleet scheme while preserving its custom paint.")
                                      {:name "scheme"}) (scheme-options schemes (:scheme draft))]]
     [:button {:type "submit" :disabled (nil? (:class-id draft))} "Create ship"]]]
   (when record
     (list
      [:p (str (:name draft) " · " (:class-name draft) " class")]
      [:form#paint-scheme (merge selection-attrs {:method "post" :action "/ships/paint/select" :hx-post "/ships/paint/select" :hx-trigger "change"})
       [:label "Fleet scheme" [:select (merge
                                        (help/attrs "Change this ship’s fleet scheme while preserving its custom paint.")
                                        {:name "scheme"}) (scheme-options schemes (:scheme draft))]]]
      [:details [:summary "Manage ship"]
       [:form#paint-rename (merge selection-attrs {:method "post" :action "/ships/paint/rename" :hx-post "/ships/paint/rename"})
        [:label "Ship name" [:input {:name "name" :value (:name draft) :required true :maxlength 200}]]
        [:button {:type "submit"} "Rename ship"]]
       [:form#paint-reset (merge selection-attrs {:method "post" :action "/ships/paint/reset" :hx-post "/ships/paint/reset"
                                                  :hx-confirm "Reset all custom paint on this named ship? Its fleet scheme will be kept."})
        [:input {:type "hidden" :name "id" :value (str (:ship-id draft))}]
        [:input {:type "hidden" :name "confirmed" :value "true"}]
        [:button {:type "submit"} "Reset custom paint"]]
       (ship-views/named-delete-form {:ship/id (:ship-id draft) :ship/name (:name draft)} nil
                                     (assoc selection-attrs :id "paint-delete") {} "Delete ship")]))])

(defn color-control
  ([hex picker] (color-control hex picker {:name "base" :id "paint-base" :label "Hex color"}))
  ([hex picker {:keys [name id label]}]
   (let [[h s v] (color/picker-value hex picker)]
     [:div.color-picker
      [:input {:type "hidden" :name "saturation" :value s}]
      [:input {:type "hidden" :name "brightness" :value v}]
      [:div.color-spectrum {:tabindex 0 :role "slider" :aria-label "Saturation and brightness"
                            :aria-valuemin 0 :aria-valuemax 100 :aria-valuenow (* 100 s)
                            :aria-valuetext (str "Saturation " (int (* 100 s)) "%, brightness " (int (* 100 v)) "%")
                            :data-saturation s :data-brightness v
                            :style (str "--spectrum-hue:hsl(" h ",100%,50%)")}
       [:span.color-spectrum__cursor {:style (str "left:" (* 100 s) "%;top:" (* 100 (- 1 v)) "%")}]]
      [:label "Hue" [:input.color-hue {:name "hue" :type "range" :min 0 :max 359 :step 1 :value h :aria-label "Hue"}]]
      [:label label [:input {:id id :type "text" :name name :value hex :required true
                             :pattern "#[0-9a-fA-F]{6}" :maxlength 7 :spellcheck false
                             :data-paint-input "true" :data-color-value "true" :aria-label label}]]])))

(defn material-control [label name type value]
  [:label.paint-control [:span label [:output {:for (str "paint-" name)} value]]
   [:input (cond-> {:id (str "paint-" name) :type type :name name :value value :data-paint-input "true"
                    :oninput "this.parentElement.querySelector('output').value=this.value"}
             (= type "range") (assoc :min 0 :max 1 :step 0.01))]])

(defn brush-panel [record targets prepared state flush-interval material]
  (let [stale (for [instance targets :when (contains? instance :path)
                    :let [layer (get-in record [:scheme/details (:path instance)])]
                    :when (and layer (or (not= (:part-id layer) (:part-id instance))
                                         (not= (:mesh-key layer) (get-in prepared [(:part-id instance) :mesh-key]))))] (:path instance))]
    [:form#paint-brush
     {:method "post" :action "/ships/paint/stroke" :hx-post "/ships/paint/stroke" :hx-target "#brush-status"
      :hx-swap "innerHTML" :hx-sync "this:queue all"
      :hx-disabled-elt (str competing-controls ", #paint-brush input:not([type=hidden]), #paint-brush select, #paint-brush button, button[form=paint-brush]")
      :data-flush-interval flush-interval :data-stale-targets (pr-str (vec stale))
      :data-instance-labels (pr-str (into {} (for [entry targets :when (contains? entry :path)] [(:key entry) (:label entry)])))
      :hx-on--config-request "var field=this.elements.sequence;field.value=Number(field.value)+1;event.detail.parameters.sequence=field.value;"
      :hx-on--after-request "if(document.contains(this)&&!event.detail.successful){document.getElementById('paint-header-status').textContent='Save not confirmed';}"}
     (for [[name value] {"id" (str (:scheme/id record)) "sequence" (or (:brush-sequence state) 0)
                         "color" "#ff0000"
                         "metalness" (:metalness material) "roughness" (:roughness material) "glow" (get material :glow 0)
                         "entries" "[]" "stroke-id" "" "part" "0" "final" "true" "enabled" "true" "operation" "paint"}]
       [:input {:type "hidden" :name name :value value}])
     [:div.paint-form-body
      [:div.paint-segmented
       [:label [:input {:type "radio" :name "mode" :value "paint" :checked true}] "Paint"]
       [:label [:input {:type "radio" :name "mode" :value "erase"}] "Erase to base"]]
      [:label.paint-control [:span "Radius (screen pixels)" [:output "20 px"]]
       [:input {:type "range" :name "radius" :min 2 :max 100 :value 20
                :oninput "this.parentElement.querySelector('output').value=this.value+' px'"}]]
      (color-control "#ff0000" nil {:name "brush-color" :id "paint-brush-color" :label "Detail hex color"})
      (material-control "Detail metalness" "brush-metalness" "range" (:metalness material))
      (material-control "Detail roughness" "brush-roughness" "range" (:roughness material))
      (material-control "Detail glow" "brush-glow" "range" (get material :glow 0))]
     [:footer.paint-actions
      [:button (merge
                (help/attrs "Undo the last detail stroke. History keeps up to 20 strokes.")
                {:type "submit" :name "history" :value "undo" :aria-label "Undo detail stroke"}) "Undo"]
      [:button (merge
                (help/attrs "Restore an undone detail stroke from the last 20 strokes.")
                {:type "submit" :name "history" :value "redo" :aria-label "Redo detail stroke"}) "Redo"]
      [:button {:type "button" :data-brush-retry "true"} "Retry last stroke"]
      [:p#brush-status {:role "status"}]]]))

(defn panel [records draft targets record error prepared state flush-interval material]
  (let [ready? (and (seq targets) record (:hull draft) (every? #(= :ready (:state %)) (vals prepared)))]
    (list
     [:span#paint-header [:code (or (:hull draft) "No model")]
      [:span#paint-header-status {:role "status"} "Saved values"]]
     [:section.paint-rail
      (ship-views/customize-table (:ships records) (:classes records) (:schemes records) (:ship-id draft) (:page records))
      (ship-controls records draft record)
      (when record
        [:section.paint-stroke-summary
         [:h3 "This stroke" [:span#paint-stroke-count "0 faces"]]
         [:div#paint-stroke-list]])
      (when record [:footer.paint-rail-footer
                    [:span#paint-face-count (str (reduce + 0 (map #(count (:faces %)) (vals (:scheme/details record)))) " painted faces")]])]
     [:section.paint-editor
      [:header.paint-inspector-header
       [:div [:h2 (if record "Detail brush" "Create a named ship")
              (when record (help/button "Detail brush" "Left-drag paints whole visible triangles across the ship; occluded surfaces are skipped. Right-drag erases to inherited material; Alt+drag orbits. Release to save."))]]]
      (when error [:p.detail__error {:role "alert"} error])
      (when-not (:hull draft) [:p "Save a class in Assembly to create a named ship."])
      (when (some #(= :running (:state %)) (vals prepared))
        [:p {:hx-get "/ships?poll=1" :hx-trigger "load delay:400ms" :hx-target "#detail"} "Preparing customize preview…"])
      (for [[id status] prepared :when (= :failed (:state status))]
        [:p.detail__error (str "Could not load " id ". " (:message status))])
      (when ready?
        (list (brush-panel record targets prepared state flush-interval material)
              (presets/panel (:preset-db records) nil)))])))
