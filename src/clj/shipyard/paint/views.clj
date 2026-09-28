(ns shipyard.paint.views
  (:require [shipyard.paint.transforms :as transforms]
            [shipyard.scheme.material :as material]
            [shipyard.workspace.views :as workspace]))

(def selection-attrs
  (merge workspace/transition-attrs {:hx-target "#detail" :hx-swap "innerHTML settle:0ms"
                                     :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition], .ship-inspector button, .ship-inspector input, .ship-inspector select"}))

(def competing-controls
  ".ship-inspector > nav button, .ship-card button, #workspace-navigation button, #paint-select select, #paint-scheme select, #paint-reset button, #paint-target button, #paint-create button, #paint-rename button, #paint-delete button, button[form=paint-default], .paint-write button, .paint-write select, .paint-group-controls button, #mount-colors-toggle")

(defn transfer-button [source label]
  [:form (merge selection-attrs {:novalidate true :method "post" :action (str "/" source "/paint") :hx-post (str "/" source "/paint")
                                 :hx-include ".assembly__save input[name=name]"})
   [:button {:type "submit" :data-workspace-transition "true"} label]])

(defn swatch [value]
  [:span.paint-swatch {:aria-hidden "true" :style (str "background:" (transforms/color-hex (:base value)))}])

(defn group-record [record target]
  (first (filter #(= (:group-id target) (:group/id %)) (:scheme/groups record))))

(defn target-row [record target entry count selected-members]
  (let [instance? (contains? entry :path) selected? (= (:key target) (:key entry))
        source (when instance? (material/material-source record (:path entry) (:part-id entry)))
        inherited (when (= :group (first source)) (material/winning-group record (:path entry) (:part-id entry)))]
    [:div.paint-target-row {:class (when selected? "is-selected")}
     (when instance?
       [:input {:type "checkbox" :name "members" :value (:key entry) :form "paint-group-create"
                :aria-label (str "Group member " (:label entry))
                :checked (contains? selected-members (select-keys entry [:path :part-id]))}])
     [:button {:type "submit" :name "target" :value (:key entry) :data-paint-target (:key entry)
               :aria-pressed (str selected?)}
      (swatch (transforms/target-material record entry))
      [:span.paint-target-name (or (:name entry) (some-> (:role entry) (name)) (:label entry))
       (when instance? [:small (str (if (empty? (:path entry)) "Hull" (pr-str (:path entry))) " · "
                                    (case (first source) :instance "instance material" :group (:group/name inherited) :layer "layer defaults" "neutral material"))])]
      (when count [:small (str count " instances")])]]))

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
     [:label "Fleet scheme" [:select {:name "scheme"} (scheme-options schemes (:scheme draft))]]
     [:button {:type "submit" :disabled (nil? (:class-id draft))} "Create ship"]]]
   (when record
     (list
      [:p (str (:name draft) " · " (:class-name draft) " class")]
      [:form#paint-scheme (merge selection-attrs {:method "post" :action "/ships/paint/select" :hx-post "/ships/paint/select" :hx-trigger "change"})
       [:label "Fleet scheme" [:select {:name "scheme"} (scheme-options schemes (:scheme draft))]]]
      [:p.muted "Custom paint stays on this ship when its scheme changes."]
      [:details [:summary "Manage ship"]
       [:form#paint-rename (merge selection-attrs {:method "post" :action "/ships/paint/rename" :hx-post "/ships/paint/rename"})
        [:label "Ship name" [:input {:name "name" :value (:name draft) :required true :maxlength 200}]]
        [:button {:type "submit"} "Rename ship"]]
       [:form#paint-reset (merge selection-attrs {:method "post" :action "/ships/paint/reset" :hx-post "/ships/paint/reset"
                                                  :hx-confirm "Reset all custom paint on this named ship? Its fleet scheme will be kept."})
        [:input {:type "hidden" :name "id" :value (str (:ship-id draft))}]
        [:input {:type "hidden" :name "confirmed" :value "true"}]
        [:button {:type "submit"} "Reset custom paint"]]
       [:form#paint-delete (merge selection-attrs {:method "post" :action "/ships/paint/delete" :hx-post "/ships/paint/delete"
                                                   :hx-confirm (str "Delete named ship “" (:name draft) "” and its custom paint? Its class and scheme will be kept.")})
        [:input {:type "hidden" :name "id" :value (str (:ship-id draft))}]
        [:input {:type "hidden" :name "confirmed" :value "true"}]
        [:button {:type "submit"} "Delete ship"]]]))
   [:p.muted "Use the Schemes tab to edit fleet palettes."]])

(defn target-tree [record targets target]
  (let [instances (filter #(contains? % :path) targets)
        members (set (:group/members (group-record record target)))]
    [:div.paint-targets
     [:form#paint-target (merge selection-attrs {:method "post" :action "/ships/paint/target" :hx-post "/ships/paint/target"})
      [:h3 "Ship layer overrides"]
      (for [entry targets :when (:layer-id entry)] (target-row record target entry nil members))
      [:h3 "Groups" [:button.paint-group-link {:type "button" :onclick "var d=document.getElementById('paint-group-disclosure');if(d){d.open=true;d.scrollIntoView({block:'nearest'});}"} "Group selection"]]
      (for [entry targets :when (:group-id entry)]
        (target-row record target entry (count (:group/members (group-record record entry))) members))
      [:h3 "Instances"]
      (for [entry instances] (target-row record target entry nil members))]
     (when record
       [:details#paint-group-disclosure.paint-group-controls [:summary "Group selection"]
        [:form#paint-group-create (merge selection-attrs {:method "post" :action "/ships/paint/group/create" :hx-post "/ships/paint/group/create"})
         [:label "Group name" [:input {:name "name" :required true :maxlength 200}]]
         [:button {:type "submit"} "Create group"]]])]))

(defn group-controls [record target]
  (when-let [group (group-record record target)]
    [:details.paint-group-controls {:open true} [:summary "Manage group"]
     [:form (merge selection-attrs {:method "post" :action "/ships/paint/group/rename" :hx-post "/ships/paint/group/rename"})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:label "Group name" [:input {:name "name" :value (:group/name group) :required true :maxlength 200}]]
      [:button {:type "submit"} "Rename group"]]
     [:form (merge selection-attrs {:method "post" :action "/ships/paint/group/members" :hx-post "/ships/paint/group/members" :hx-include "#paint-target input[name=members]"})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:button {:type "submit"} "Use checked members"]]
     [:form (merge selection-attrs {:method "post" :action "/ships/paint/group/order" :hx-post "/ships/paint/group/order"})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:button {:type "submit" :name "direction" :value "up" :disabled (zero? (:group/order group))} "Move up"]
      [:button {:type "submit" :name "direction" :value "down" :disabled (= (:group/order group) (dec (count (:scheme/groups record))))} "Move down"]]
     [:form (merge selection-attrs {:method "post" :action "/ships/paint/group/delete" :hx-post "/ships/paint/group/delete" :hx-confirm "Delete this group? Instance materials and face details are preserved."})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:button {:type "submit"} "Delete group"]]]))

(defn write-targets [record targets target anchor-key]
  (let [belongs? (fn [entry]
                   (and (contains? entry :path)
                        (or (not (:group-id target))
                            (some #{(select-keys entry [:path :part-id])} (:group/members (group-record record target))))))
        anchor (or (when (contains? target :path) target)
                   (first (filter #(and (= anchor-key (:key %)) (belongs? %)) targets))
                   (first (filter belongs? targets)))
        groups (material/groups-for record (:path anchor) (:part-id anchor))
        group (or (group-record record target) (first groups))]
    [:div.paint-write
     [:h3 "Write to"]
     [:form.paint-segmented (merge selection-attrs {:method "post" :action "/ships/paint/target" :hx-post "/ships/paint/target"})
      (for [[label key selected?] [["Instance" (:key anchor) (contains? target :path)]
                                   ["Layer" (when anchor "layer/Primary") (some? (:layer-id target))]
                                   ["Group" (when group (str "group/" (:group/id group))) (some? (:group-id target))]]]
        [:button {:type "submit" :name "target" :value key :disabled (nil? key) :aria-pressed (str selected?)} label])]
     (when (and (:group-id target) (> (count groups) 1))
       [:form (merge selection-attrs {:method "post" :action "/ships/paint/target" :hx-post "/ships/paint/target" :hx-trigger "change"})
        [:label "Material group" [:select {:name "target"}
                                  (for [g groups] [:option {:value (str "group/" (:group/id g)) :selected (= (:group-id target) (:group/id g))} (:group/name g)])]]])]))

(defn material-control [label name type value]
  [:label.paint-control [:span label [:output {:for (str "paint-" name)} value]]
   [:input (cond-> {:id (str "paint-" name) :type type :name name :value value :data-paint-input "true"
                    :oninput "this.parentElement.querySelector('output').value=this.value"}
             (= type "range") (assoc :min 0 :max 1 :step 0.01))]])

(defn material-form [record target value paths sequence]
  [:form#paint-material
   {:method "post" :action "/ships/paint/material" :hx-post "/ships/paint/material" :hx-target "#paint-status" :hx-swap "innerHTML"
    :hx-trigger "change, submit" :hx-sync "this:queue last" :hx-disabled-elt competing-controls
    :data-paint-slots (pr-str paths)
    :data-paint-layer (:layer-id target)
    :data-paint-override (str (boolean (or (contains? target :path) (:group-id target))))
    :hx-on:input "this.elements.sequence.value=Number(this.elements.sequence.value)+1;document.getElementById('paint-status').textContent='Preview not saved';document.getElementById('paint-header-status').textContent='Preview not saved';"
    :hx-on--config-request "var field=this.elements.sequence;field.value=Number(field.value)+1;event.detail.parameters.sequence=field.value;"
    :hx-on--before-request "document.getElementById('paint-status').textContent='Saving…';document.getElementById('paint-header-status').textContent='Saving…';"
    :hx-on--after-request "if(String(event.detail.requestConfig.parameters.sequence)!==this.elements.sequence.value){document.getElementById('paint-status').textContent='Newer preview not saved';}document.getElementById('paint-header-status').textContent=document.getElementById('paint-status').textContent;"}
   [:input {:type "hidden" :name "id" :value (str (:scheme/id record))}]
   [:input {:type "hidden" :name "target" :value (:key target)}]
   [:input {:type "hidden" :name "sequence" :value sequence}]
   [:div.paint-form-body
    (material-control "Base colour" "base" "color" (transforms/color-hex (:base value)))
    (material-control "Metalness" "metalness" "range" (:metalness value))
    (material-control "Roughness" "roughness" "range" (:roughness value))
    (material-control "Glow" "glow" "range" (get value :glow 0))
    [:label "Paint name" [:input {:name "paint" :maxlength 200 :value (or (:paint value) "")}]]]
   [:footer.paint-actions
    [:button.paint-primary {:type "submit"} "Save material"]
    (when (or (contains? target :path) (:layer-id target) (:group-id target))
      [:button {:type "submit" :form "paint-default"} "Use inherited material"])
    [:p#paint-status {:role "status"} "Saved values"]]])

(defn brush-panel [record target targets prepared state flush-interval material]
  (let [brush? (= "brush" (:tool state))
        stale (for [instance targets :when (contains? instance :path)
                    :let [layer (get-in record [:scheme/details (:path instance)])]
                    :when (and layer (or (not= (:part-id layer) (:part-id instance))
                                         (not= (:mesh-key layer) (get-in prepared [(:part-id instance) :mesh-key]))))] (:path instance))]
    [:form#paint-brush
     {:hidden (not brush?) :method "post" :action "/ships/paint/stroke" :hx-post "/ships/paint/stroke" :hx-target (if brush? "#brush-status" "#paint-status")
      :hx-swap "innerHTML" :hx-sync "this:queue all"
      :hx-disabled-elt (str competing-controls ", #paint-brush input:not([type=hidden]), #paint-brush select, #paint-brush button, .paint-tools button, button[form=paint-brush]")
      :data-flush-interval flush-interval :data-stale-targets (pr-str (vec stale))
      :data-instance-labels (pr-str (into {} (for [entry targets :when (contains? entry :path)] [(:key entry) (:label entry)])))
      :hx-on--config-request "var field=this.elements.sequence;field.value=Number(field.value)+1;event.detail.parameters.sequence=field.value;"
      :hx-on--after-request "if(document.contains(this)&&!event.detail.successful){document.getElementById('paint-header-status').textContent='Save not confirmed';}"}
     (for [[name value] {"id" (str (:scheme/id record)) "target" (:key target) "sequence" (or (:brush-sequence state) 0)
                         "mesh-key" (get-in prepared [(:part-id target) :mesh-key]) "faces" "[]" "color" "#ff0000"
                         "metalness" (:metalness material) "roughness" (:roughness material) "glow" (get material :glow 0)
                         "entries" "[]" "stroke-id" "" "part" "0" "final" "true" "enabled" (str brush?) "operation" "paint"}]
       [:input {:type "hidden" :name name :value value}])
     [:div.paint-form-body
      [:div.paint-segmented
       [:label [:input {:type "radio" :name "mode" :value "paint" :checked true}] "Paint"]
       [:label [:input {:type "radio" :name "mode" :value "erase"}] "Erase to base"]]
      [:label.paint-control [:span "Radius (screen pixels)" [:output "20 px"]]
       [:input {:type "range" :name "radius" :min 2 :max 100 :value 20
                :oninput "this.parentElement.querySelector('output').value=this.value+' px'"}]]
      [:label.paint-checkbox [:input {:type "checkbox" :name "cross-instances" :checked true}] "Cross instances"]
      (material-control "Detail colour" "brush-color" "color" "#ff0000")
      (material-control "Detail metalness" "brush-metalness" "range" (:metalness material))
      (material-control "Detail roughness" "brush-roughness" "range" (:roughness material))
      (material-control "Detail glow" "brush-glow" "range" (get material :glow 0))
      [:p.muted "Left-drag paints; right-drag erases to the inherited material. Turn off Mount colors to paint. Alt+drag to orbit."]]
     [:footer.paint-actions
      [:button {:type "submit" :name "history" :value "undo" :aria-label "Undo detail stroke"} "Undo"]
      [:button {:type "submit" :name "history" :value "redo" :aria-label "Redo detail stroke"} "Redo"]
      [:button {:type "button" :data-brush-retry "true"} "Retry last stroke"]
      [:p#brush-status {:role "status"} "Release to save. Undo keeps the last 20 strokes."]]]))

(defn panel [records draft targets target record value paths sequence error prepared anchor-key state flush-interval]
  (let [brush? (and record (:hull draft) (= "brush" (:tool state)))
        ready? (and target record (every? #(= :ready (:state %)) (vals prepared)))
        model-ready? (and ready? (:hull draft))]
    (list
     [:span#paint-header
      [:code (or (:hull draft) "No model")]
      [:span#paint-header-status {:role "status"} "Saved values"]]
     (when (and record (:hull draft)) [:div.paint-tools
                                       [:form.paint-segmented (merge selection-attrs {:method "post" :action "/ships/paint/tool" :hx-post "/ships/paint/tool"})
                                        [:button {:type "submit" :name "tool" :data-workspace-transition "true" :value "select" :aria-pressed (str (not brush?))} "Select"]
                                        [:button {:type "submit" :name "tool" :data-workspace-transition "true" :value "brush" :aria-pressed (str brush?) :disabled (not model-ready?)} "Brush"]]
                                       (when brush? [:span "Orbit Alt+drag"])])
     [:section.paint-rail
      (ship-controls records draft record)
      (if brush?
        [:section.paint-stroke-summary
         [:h3 "This stroke" [:span#paint-stroke-count "0 faces"]]
         [:div#paint-stroke-list]
         [:p.muted "Whole visible triangles. Occluded surfaces are skipped."]]
        (when record (target-tree record targets target)))
      (when record [:footer.paint-rail-footer
                    [:span#paint-face-count (str (reduce + 0 (map #(count (:faces %)) (vals (:scheme/details record)))) " painted faces")]
                    [:button {:type "submit" :form "paint-brush" :name "history" :value "clear"
                              :disabled (not (and ready? (contains? target :path)))
                              :onclick "return window.confirm('Clear all details on this instance?')"} "Clear instance details"]])]
     [:section.paint-editor
      [:header.paint-inspector-header
       [:div [:h2 (cond (nil? record) "Create a named ship" brush? "Detail brush" :else (or (:name target) (:label target) "Paint preview"))]
        [:p (if brush? "Whole visible triangles, nearest surface only"
                (when (and record target) (str (if (contains? target :path) (pr-str (:path target)) (:key target))
                                               (when (:role target) (str " · role " (name (:role target)))))))]]
       (when (and record value) (swatch value))]
      (when-not record [:p "Name a ship of this class and choose its fleet scheme. Custom paint belongs to that named ship."])
      (when error [:p.detail__error {:role "alert"} error])
      (when-not (:hull draft) [:p "Save a ship class in Assemble, then create a named ship here or from Ship Browser."])
      (when (some #(= :running (:state %)) (vals prepared))
        [:p {:hx-get "/ships?poll=1" :hx-trigger "load delay:400ms" :hx-target "#detail"} "Preparing paint preview…"])
      (for [[id status] prepared :when (= :failed (:state status))]
        [:p.detail__error (str "Could not load " id ". " (:message status))])
      (when ready?
        (list (when-not brush?
                (list (when-not (:layer-id target) (write-targets record targets target anchor-key))
                      (group-controls record target)
                      (material-form record target value paths sequence)))
              (when model-ready? (brush-panel record target targets prepared state flush-interval value))))
      (when (and record target)
        [:form#paint-default (merge selection-attrs {:method "post" :action "/ships/paint/default" :hx-post "/ships/paint/default"})])]
     (when record [:div.paint-legend
                   (if brush? (list [:span "Brush radius (screen px)"] [:span "Painted this stroke"] [:span "Occluded — skipped"])
                       (list [:span "Selected target"] [:span "Fleet scheme"] [:span "Group material"] [:span "Instance material"]))]))))
