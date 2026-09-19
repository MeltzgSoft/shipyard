(ns shipyard.paint.views
  (:require [shipyard.paint.transforms :as transforms]
            [shipyard.workspace.views :as workspace]))

(def selection-attrs
  (merge workspace/transition-attrs {:hx-target "#detail" :hx-swap "innerHTML settle:0ms"}))

(defn transfer-button [source label]
  [:form (merge selection-attrs {:novalidate true :hx-post (str "/" source "/paint")
                                 :hx-include ".assembly__save input[name=name]"})
   [:button {:type "submit" :data-workspace-transition "true"} label]])

(defn brush-panel [record target prepared brush-sequence material]
  (when (and record (contains? target :path) (= :ready (get-in prepared [(:part-id target) :state])))
    [:form#paint-brush
     {:hx-post "/paint/stroke" :hx-target "#brush-status" :hx-swap "innerHTML" :hx-sync "this:drop"
      :hx-disabled-elt "#paint-brush input:not([type=hidden]), #paint-brush select, #paint-brush button, #paint-material input, #paint-material button, #workspace-navigation button, #paint-select select, #paint-target select, #paint-create button, #paint-rename button, #paint-default button, #mount-colors-toggle"
      :hx-on--config-request "var field=this.elements.sequence;field.value=Number(field.value)+1;event.detail.parameters.sequence=field.value;"
      :hx-on--before-request "document.getElementById('brush-status').textContent='Saving stroke…';"
      :hx-on--after-request "if(document.contains(this)&&!event.detail.successful){document.getElementById('brush-status').textContent='Save not confirmed. Retry last stroke, or reopen Paint to restore saved details.';}"}
     [:h3 "Detail brush"]
     [:p "Paints colour, metalness and roughness on whole visible triangles of this instance. Turn off Mount colors to paint. Alt+drag to orbit."]
     [:input {:type "hidden" :name "id" :value (str (:scheme/id record))}]
     [:input {:type "hidden" :name "target" :value (:key target)}]
     [:input {:type "hidden" :name "mesh-key" :value (get-in prepared [(:part-id target) :mesh-key])}]
     [:input {:type "hidden" :name "sequence" :value brush-sequence}]
     [:input {:type "hidden" :name "faces" :value "[]"}]
     [:input {:type "hidden" :name "color" :value "#ff0000"}]
     [:input {:type "hidden" :name "metalness" :value (:metalness material)}]
     [:input {:type "hidden" :name "roughness" :value (:roughness material)}]
     [:input {:type "hidden" :name "operation" :value "paint"}]
     [:label [:input {:type "checkbox" :name "enabled"}] "Enable brush"]
     [:label "Detail colour" [:input {:type "color" :name "brush-color" :value "#ff0000"}]]
     [:label "Detail metalness" [:input {:type "range" :name "brush-metalness" :min 0 :max 1 :step 0.01 :value (:metalness material)}]]
     [:label "Detail roughness" [:input {:type "range" :name "brush-roughness" :min 0 :max 1 :step 0.01 :value (:roughness material)}]]
     [:label "Radius (screen pixels)" [:input {:type "range" :name "radius" :min 2 :max 100 :value 20}]]
     [:label "Mode" [:select {:name "mode"} [:option {:value "paint"} "Paint"] [:option {:value "erase"} "Erase to base"]]]
     [:button {:type "submit"} "Retry last stroke"]
     [:button {:type "submit" :name "history" :value "undo"} "Undo detail stroke"]
     [:button {:type "submit" :name "history" :value "redo"} "Redo detail stroke"]
     [:button {:type "submit" :name "history" :value "clear" :onclick "return window.confirm('Clear all details on this instance?')"} "Clear instance details"]
     [:p#brush-status {:role "status"} "Release to save. Up to 1,024 faces per stroke; undo keeps the last 20 strokes for this selection."]]))

(defn panel [records draft targets target record value paths sequence error prepared brush-sequence]
  (list
   [:section#library.panel {:hx-swap-oob "outerHTML"}
    [:h2.panel__title "Paint"]
    [:p "Copy a model here with Paint assembly or Paint ship. Navigation retains this preview."]
    [:form#paint-select (merge selection-attrs {:hx-post "/paint/select" :hx-trigger "change"})
     [:label "Scheme" [:select {:name "id"}
                       [:option {:value ""} "No scheme"]
                       (for [r (sort-by :scheme/name records)]
                         [:option {:value (str (:scheme/id r)) :selected (= (:scheme/id r) (:scheme draft))} (:scheme/name r)])]]]
    [:form#paint-create (merge selection-attrs {:hx-post "/paint/create"})
     [:label "New scheme name" [:input {:name "name" :required true :maxlength 200}]]
     [:button {:type "submit"} "Create scheme"]]
    (when record
      [:form#paint-rename (merge selection-attrs {:hx-post "/paint/rename"})
       [:label "Scheme name" [:input {:name "name" :value (:scheme/name record) :required true :maxlength 200}]]
       [:button {:type "submit"} "Rename scheme"]])
    (when (seq targets)
      [:form#paint-target (merge selection-attrs {:hx-post "/paint/target" :hx-trigger "change"})
       [:label "Paint target" [:select {:name "target"}
                               (for [entry targets]
                                 [:option {:value (:key entry) :selected (= (:key entry) (:key target))} (:label entry)])]]])]
   [:section.paint-editor
    [:h2 (or (:scheme/name record) "Paint preview")]
    [:p "Scheme edits affect every saved ship using this scheme. Assign it to a ship in Assemble, then Save ship."]
    (when error [:p.detail__error {:role "alert"} error])
    (when-not (:hull draft) [:p "No paint model selected. Use Paint assembly or Paint ship to copy a model here."])
    (when (some #(= :running (:state %)) (vals prepared))
      [:p {:hx-get "/paint?poll=1" :hx-trigger "load delay:400ms" :hx-target "#detail"} "Preparing paint preview…"])
    (for [[id status] prepared :when (= :failed (:state status))]
      [:p.detail__error (str "Could not load " id ". " (:message status))])
    (when (and target record (every? #(= :ready (:state %)) (vals prepared)))
      [:form#paint-material
       {:hx-post "/paint/material" :hx-target "#paint-status" :hx-swap "innerHTML"
        :hx-trigger "change, submit" :hx-sync "this:queue last"
        :hx-disabled-elt "#paint-brush input:not([type=hidden]), #paint-brush select, #paint-brush button, #workspace-navigation button, #paint-select select, #paint-target select, #paint-create button, #paint-rename button, #paint-default button, #mount-colors-toggle"
        :data-paint-slots (pr-str paths)
        :hx-on:input "this.elements.sequence.value=Number(this.elements.sequence.value)+1;document.getElementById('paint-status').textContent='Preview not saved';"
        :hx-on--config-request "var field=this.elements.sequence;field.value=Number(field.value)+1;event.detail.parameters.sequence=field.value;"
        :hx-on--before-request "document.getElementById('paint-status').textContent='Saving…';"
        :hx-on--after-request "if(String(event.detail.requestConfig.parameters.sequence)!==this.elements.sequence.value){document.getElementById('paint-status').textContent='Newer preview not saved';}"}
       [:input {:type "hidden" :name "id" :value (str (:scheme/id record))}]
       [:input {:type "hidden" :name "target" :value (:key target)}]
       [:input {:type "hidden" :name "sequence" :value sequence}]
       [:label "Base colour" [:input {:type "color" :name "base" :value (transforms/color-hex (:base value)) :data-paint-input "true"}]]
       [:label "Metalness" [:input {:type "range" :name "metalness" :min 0 :max 1 :step 0.01 :value (:metalness value) :data-paint-input "true"}]]
       [:label "Roughness" [:input {:type "range" :name "roughness" :min 0 :max 1 :step 0.01 :value (:roughness value) :data-paint-input "true"}]]
       [:label "Paint name" [:input {:name "paint" :maxlength 200 :value (or (:paint value) "")}]]
       [:button {:type "submit"} "Save material / Retry"]
       [:p#paint-status {:role "status"} "Saved values"]])
    (when (and record target (contains? target :path))
      [:form#paint-default (merge selection-attrs {:hx-post "/paint/default"})
       [:button {:type "submit"} "Use role default"]])
    (brush-panel record target prepared brush-sequence value)]))
