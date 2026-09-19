(ns shipyard.paint.views
  (:require [shipyard.paint.transforms :as transforms]
            [shipyard.scheme.material :as material]
            [shipyard.workspace.views :as workspace]))

(def selection-attrs
  (merge workspace/transition-attrs {:hx-target "#detail" :hx-swap "innerHTML settle:0ms"}))

(def competing-controls
  "#workspace-navigation button, #paint-select select, #paint-target button, #paint-create button, #paint-rename button, button[form=paint-default], .paint-write button, .paint-write select, .paint-group-controls button, #mount-colors-toggle")

(defn transfer-button [source label]
  [:form (merge selection-attrs {:novalidate true :hx-post (str "/" source "/paint")
                                 :hx-include ".assembly__save input[name=name]"})
   [:button {:type "submit" :data-workspace-transition "true"} label]])

(defn swatch [value]
  [:span.paint-swatch {:aria-hidden "true" :style (str "background:" (transforms/color-hex (:base value)))}])

(defn group-record [record target]
  (first (filter #(= (:group-id target) (:group/id %)) (:scheme/groups record))))

(defn target-row [record target entry count selected-members]
  (let [instance? (contains? entry :path) selected? (= (:key target) (:key entry))
        source (when instance? (material/material-source record (:path entry) (:part-id entry) (:role entry)))
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
                                    (case (first source) :instance "instance material" :group (:group/name inherited) "role default"))])]
      (when count [:small (str count " instances")])]]))

(defn scheme-controls [records draft record]
  [:div.paint-scheme
   [:form#paint-select (merge selection-attrs {:hx-post "/paint/select" :hx-trigger "change"})
    [:label "Scheme" [:select {:name "id"}
                      [:option {:value ""} "No scheme"]
                      (for [r (sort-by :scheme/name records)]
                        [:option {:value (str (:scheme/id r)) :selected (= (:scheme/id r) (:scheme draft))} (:scheme/name r)])]]]
   [:div.paint-scheme-actions
    [:details [:summary "New"]
     [:form#paint-create (merge selection-attrs {:hx-post "/paint/create"})
      [:label "New scheme name" [:input {:name "name" :required true :maxlength 200}]]
      [:button {:type "submit"} "Create scheme"]]]
    (when record
      [:details [:summary "Rename"]
       [:form#paint-rename (merge selection-attrs {:hx-post "/paint/rename"})
        [:label "Scheme name" [:input {:name "name" :value (:scheme/name record) :required true :maxlength 200}]]
        [:button {:type "submit"} "Rename scheme"]]])]
   (if record
     [:p "Edits affect every saved ship using this scheme."]
     [:p.paint-empty "Choose a scheme, or open New and name a scheme to start painting."])])

(defn target-tree [record targets target]
  (let [instances (filter #(contains? % :path) targets)
        members (set (:group/members (group-record record target)))]
    [:div.paint-targets
     [:form#paint-target (merge selection-attrs {:hx-post "/paint/target"})
      [:h3 "Role defaults"]
      (for [entry targets :when (and (:role entry) (not (contains? entry :path)))]
        (target-row record target entry (count (filter #(= (:role entry) (:role %)) instances)) members))
      [:h3 "Groups" [:button.paint-group-link {:type "button" :onclick "var d=document.getElementById('paint-group-disclosure');if(d){d.open=true;d.scrollIntoView({block:'nearest'});}"} "Group selection"]]
      (for [entry targets :when (:group-id entry)]
        (target-row record target entry (count (:group/members (group-record record entry))) members))
      [:h3 "Instances"]
      (for [entry instances] (target-row record target entry nil members))]
     (when record
       [:details#paint-group-disclosure.paint-group-controls [:summary "Group selection"]
        [:form#paint-group-create (merge selection-attrs {:hx-post "/paint/group/create"})
         [:label "Group name" [:input {:name "name" :required true :maxlength 200}]]
         [:button {:type "submit"} "Create group"]]])]))

(defn group-controls [record target]
  (when-let [group (group-record record target)]
    [:details.paint-group-controls {:open true} [:summary "Manage group"]
     [:form (merge selection-attrs {:hx-post "/paint/group/rename"})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:label "Group name" [:input {:name "name" :value (:group/name group) :required true :maxlength 200}]]
      [:button {:type "submit"} "Rename group"]]
     [:form (merge selection-attrs {:hx-post "/paint/group/members" :hx-include "#paint-target input[name=members]"})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:button {:type "submit"} "Use checked members"]]
     [:form (merge selection-attrs {:hx-post "/paint/group/order"})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:button {:type "submit" :name "direction" :value "up" :disabled (zero? (:group/order group))} "Move up"]
      [:button {:type "submit" :name "direction" :value "down" :disabled (= (:group/order group) (dec (count (:scheme/groups record))))} "Move down"]]
     [:form (merge selection-attrs {:hx-post "/paint/group/delete" :hx-confirm "Delete this group? Instance materials and face details are preserved."})
      [:input {:type "hidden" :name "group" :value (str (:group/id group))}]
      [:button {:type "submit"} "Delete group"]]]))

(defn write-targets [record targets target anchor-key]
  (let [belongs? (fn [entry]
                   (and (contains? entry :path)
                        (if (:group-id target)
                          (some #{(select-keys entry [:path :part-id])} (:group/members (group-record record target)))
                          (= (:role target) (:role entry)))))
        anchor (or (when (contains? target :path) target)
                   (first (filter #(and (= anchor-key (:key %)) (belongs? %)) targets))
                   (first (filter belongs? targets)))
        groups (material/groups-for record (:path anchor) (:part-id anchor))
        group (or (group-record record target) (first groups))]
    [:div.paint-write
     [:h3 "Write to"]
     [:form.paint-segmented (merge selection-attrs {:hx-post "/paint/target"})
      (for [[label key selected?] [["Instance" (:key anchor) (contains? target :path)]
                                   ["Role" (when (:role anchor) (str "role/" (name (:role anchor))))
                                    (and (:role target) (not (contains? target :path)))]
                                   ["Group" (when group (str "group/" (:group/id group))) (some? (:group-id target))]]]
        [:button {:type "submit" :name "target" :value key :disabled (nil? key) :aria-pressed (str selected?)} label])]
     (when (and (:group-id target) (> (count groups) 1))
       [:form (merge selection-attrs {:hx-post "/paint/target" :hx-trigger "change"})
        [:label "Material group" [:select {:name "target"}
                                  (for [g groups] [:option {:value (str "group/" (:group/id g)) :selected (= (:group-id target) (:group/id g))} (:group/name g)])]]])]))

(defn material-control [label name type value]
  [:label.paint-control [:span label [:output {:for (str "paint-" name)} value]]
   [:input (cond-> {:id (str "paint-" name) :type type :name name :value value :data-paint-input "true"
                    :oninput "this.parentElement.querySelector('output').value=this.value"}
             (= type "range") (assoc :min 0 :max 1 :step 0.01))]])

(defn material-form [record target value paths sequence]
  [:form#paint-material
   {:hx-post "/paint/material" :hx-target "#paint-status" :hx-swap "innerHTML"
    :hx-trigger "change, submit" :hx-sync "this:queue last" :hx-disabled-elt competing-controls
    :data-paint-slots (pr-str paths)
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
    [:label "Paint name" [:input {:name "paint" :maxlength 200 :value (or (:paint value) "")}]]]
   [:footer.paint-actions
    [:button.paint-primary {:type "submit"} "Save material"]
    (when (contains? target :path)
      [:button {:type "submit" :form "paint-default"} "Use inherited material"])
    [:p#paint-status {:role "status"} "Saved values"]]])

(defn brush-panel [record target targets prepared state flush-interval material]
  (let [brush? (= "brush" (:tool state))
        stale (for [instance targets :when (contains? instance :path)
                    :let [layer (get-in record [:scheme/details (:path instance)])]
                    :when (and layer (or (not= (:part-id layer) (:part-id instance))
                                         (not= (:mesh-key layer) (get-in prepared [(:part-id instance) :mesh-key]))))] (:path instance))]
    [:form#paint-brush
     {:hidden (not brush?) :hx-post "/paint/stroke" :hx-target (if brush? "#brush-status" "#paint-status")
      :hx-swap "innerHTML" :hx-sync "this:queue all"
      :hx-disabled-elt (str competing-controls ", #paint-brush input:not([type=hidden]), #paint-brush select, #paint-brush button, .paint-tools button, button[form=paint-brush]")
      :data-flush-interval flush-interval :data-stale-targets (pr-str (vec stale))
      :data-instance-labels (pr-str (into {} (for [entry targets :when (contains? entry :path)] [(:key entry) (:label entry)])))
      :hx-on--config-request "var field=this.elements.sequence;field.value=Number(field.value)+1;event.detail.parameters.sequence=field.value;"
      :hx-on--after-request "if(document.contains(this)&&!event.detail.successful){document.getElementById('paint-header-status').textContent='Save not confirmed';}"}
     (for [[name value] {"id" (str (:scheme/id record)) "target" (:key target) "sequence" (or (:brush-sequence state) 0)
                         "mesh-key" (get-in prepared [(:part-id target) :mesh-key]) "faces" "[]" "color" "#ff0000"
                         "metalness" (:metalness material) "roughness" (:roughness material)
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
      [:p.muted "Turn off Mount colors to paint. Alt+drag to orbit."]]
     [:footer.paint-actions
      [:button {:type "submit" :name "history" :value "undo" :aria-label "Undo detail stroke"} "Undo"]
      [:button {:type "submit" :name "history" :value "redo" :aria-label "Redo detail stroke"} "Redo"]
      [:button {:type "button" :data-brush-retry "true"} "Retry last stroke"]
      [:p#brush-status {:role "status"} "Release to save. Undo keeps the last 20 strokes."]]]))

(defn panel [records draft targets target record value paths sequence error prepared anchor-key state flush-interval]
  (let [brush? (and record (= "brush" (:tool state)))
        ready? (and target record (every? #(= :ready (:state %)) (vals prepared)))]
    (list
     [:span#paint-header {:hx-swap-oob "outerHTML"}
      [:code (or (:hull draft) "No model")]
      [:span#paint-header-status {:role "status"} "Saved values"]]
     [:section#library.panel.paint-rail {:hx-swap-oob "outerHTML"}
      (scheme-controls records draft record)
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
       [:div [:h2 (cond (nil? record) "Choose a scheme" brush? "Detail brush" :else (or (:name target) (:label target) "Paint preview"))]
        [:p (if brush? "Whole visible triangles, nearest surface only"
                (when (and record target) (str (if (contains? target :path) (pr-str (:path target)) (:key target))
                                               (when (:role target) (str " · role " (name (:role target)))))))]]
       (when (and record value) (swatch value))]
      (when-not record [:p "Choose a scheme from the left rail, or open New and enter a name. Material and group controls appear after you create it."])
      (when error [:p.detail__error {:role "alert"} error])
      (when-not (:hull draft) [:p "No paint model selected. Use Paint assembly or Paint ship to copy a model here."])
      (when (some #(= :running (:state %)) (vals prepared))
        [:p {:hx-get "/paint?poll=1" :hx-trigger "load delay:400ms" :hx-target "#detail"} "Preparing paint preview…"])
      (for [[id status] prepared :when (= :failed (:state status))]
        [:p.detail__error (str "Could not load " id ". " (:message status))])
      (when ready?
        (list (when-not brush?
                (list (write-targets record targets target anchor-key)
                      (group-controls record target)
                      (material-form record target value paths sequence)))
              (brush-panel record target targets prepared state flush-interval value)))
      (when (and record target (contains? target :path))
        [:form#paint-default (merge selection-attrs {:hx-post "/paint/default"})])]
     (when record [:div.paint-tools
                   [:form.paint-segmented (merge selection-attrs {:hx-post "/paint/tool"})
                    [:button {:type "submit" :name "tool" :data-workspace-transition "true" :value "select" :aria-pressed (str (not brush?))} "Select"]
                    [:button {:type "submit" :name "tool" :data-workspace-transition "true" :value "brush" :aria-pressed (str brush?) :disabled (not ready?)} "Brush"]]
                   (when brush? [:span "Orbit Alt+drag"])])
     (when record [:div.paint-legend
                   (if brush? (list [:span "Brush radius (screen px)"] [:span "Painted this stroke"] [:span "Occluded — skipped"])
                       (list [:span "Selected target"] [:span "Role default"] [:span "Group material"] [:span "Instance material"]))]))))
