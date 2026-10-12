(ns shipyard.fleet.views
  "Server-rendered fleet list and individual editor navigation."
  (:require [clojure.string :as str]
            [shipyard.http.pagination :as pagination]
            [shipyard.workspace.views :as workspace]))

(def include "#fleet-filters, #fleet-table-position")

(defn- open-row [id]
  (str "if(!event.target.closest('button,input,select,form')){var f=document.getElementById('fleet-open-form');"
       "f.elements.id.value='" id "';htmx.trigger(f,'submit');}"))

(defn results [entries filters]
  (let [q (str/lower-case (or (get filters "q") ""))
        matches (filter #(str/includes? (str/lower-case (get-in % [:fleet :fleet/name])) q) entries)
        window (pagination/window matches (get filters "page"))]
    [:div#fleet-results.fleet-table__results
     {:data-query (get filters "q" "")
      :data-scroll-top (or (get filters "table-scroll") "0")
      :onscroll "document.querySelector('#fleet-table-position input[name=table-scroll]').value=this.scrollTop"
      :hx-on--load "if(event.target===this){this.scrollTop=Number(this.dataset.scrollTop||0)}"}
     (pagination/controls window "/fleets" "#fleet-results" include)
     (if (seq matches)
       [:table.fleet-table__table
        [:thead [:tr [:th "Fleet"] [:th "Classes"] [:th "Ships"] [:th "Extra parts"] [:th "Export readiness"]]]
        [:tbody (for [{:keys [fleet plan]} (:items window)
                      :let [id (:fleet/id fleet)]]
                  [:tr {:tabindex 0 :role "button" :data-fleet-id (str id)
                        :aria-label (str "Open fleet " (:fleet/name fleet))
                        :hx-on:dblclick (open-row id)
                        :hx-on:keydown (str "if(event.key==='Enter'&&event.target===this){event.preventDefault();" (open-row id) "}")}
                   [:td (:fleet/name fleet)]
                   [:td (count (:fleet/entries fleet))]
                   [:td (reduce +' 0 (vals (:fleet/entries fleet)))]
                   [:td (count (:fleet/extras fleet))]
                   [:td (if (:ready? plan) "Ready" "Needs attention")]])]]
       [:p "No fleets match."])]))

(defn table [entries filters error]
  [:section#library.panel.fleet-table
   {:hx-swap-oob "outerHTML" :data-fleet-view "table"
    :hx-on--config-request "var r=this.querySelector('#fleet-results');if(!event.detail.elt.closest('#fleet-filters,.table-pages')){event.detail.parameters['table-scroll']=String(r.scrollTop)}"
    :hx-on--load "if(event.target===this){var r=this.querySelector('#fleet-results');r.scrollTop=Number(r.dataset.scrollTop||0)}"}
   [:header.bulk-orient__head [:h2 "Fleet Browser"]
    [:form (merge workspace/transition-attrs
                  {:method "post" :action "/fleets/create" :hx-post "/fleets/create" :hx-target "#detail" :hx-include include})
     [:label "Fleet name" [:input {:name "name" :required true :maxlength 120}]]
     [:button {:type "submit" :data-workspace-transition true} "New fleet"]]]
   (when error [:p.detail__error {:role "alert"} error])
   [:form#fleet-filters.filters
    {:data-workspace-filters true :hx-get "/fleets" :hx-target "#fleet-results" :hx-swap "outerHTML"
     :hx-trigger "search, keyup changed delay:300ms" :hx-sync "this:replace"
     :hx-vals "{\"page\":\"1\",\"table-scroll\":\"0\"}"}
    [:label "Name" [:input {:type "search" :name "q" :value (get filters "q") :placeholder "Fleet name"}]]]
   [:form#fleet-table-position
    [:input {:type "hidden" :name "table-scroll" :value (or (get filters "table-scroll") "0")}]
    [:input {:type "hidden" :name "page" :value (or (get filters "page") "1")}]]
   [:form#fleet-open-form
    (merge workspace/transition-attrs {:hidden true :method "post" :action "/fleets/open" :hx-post "/fleets/open"
                                       :hx-target "#detail" :hx-include include})
    [:input {:type "hidden" :name "id"}]]
   (results entries filters)])

(defn- edit-form [fleet action attrs children]
  (into [:form (merge {:method "post" :action "/fleets/edit" :hx-post "/fleets/edit"
                       :hx-target "#detail" :hx-swap "innerHTML settle:0ms"
                       :hx-sync "#workspace-navigation:drop" :hx-disabled-elt "find button"} attrs)
         [:input {:type "hidden" :name "id" :value (str (:fleet/id fleet))}]
         [:input {:type "hidden" :name "action" :value action}]] children))

(defn editor [fleet {:keys [error message plan classes parts selected-class preview export-controls]}]
  (list
   [:section#library.panel {:hx-swap-oob "outerHTML" :data-fleet-view "editor"}]
   [:section#fleet-editor.fleet-editor.ship-inspector {:data-fleet-id (str (:fleet/id fleet))}
    [:button (merge workspace/transition-attrs {:type "button" :data-workspace-transition true
                                                :hx-get "/workspace/fleets?table=1" :hx-target "#detail"}) "← Back to fleets"]
    [:h2 (:fleet/name fleet)]
    (when error [:p.detail__error {:role "alert"} error])
    (when message [:p {:role "status"} message])
    (edit-form fleet "rename" {:id "fleet-name"}
               [[:label "Fleet name" [:input {:name "name" :value (:fleet/name fleet) :required true :maxlength 120}]]
                [:button {:type "submit"} "Rename"]])
    [:h3 "Classes"]
    (edit-form fleet "class-set" {:id "fleet-add-class"}
               [[:label "Saved class" [:select {:name "class-id" :required true}
                                       [:option {:value ""} "Choose a class"]
                                       (for [class (sort-by :loadout/name (vals classes))]
                                         [:option {:value (str (:loadout/id class))} (:loadout/name class)])]]
                [:label "Quantity" [:input {:type "number" :name "quantity" :min 1 :step 1 :value 1 :required true}]]
                [:button {:type "submit" :disabled (empty? classes)} "Add class"]])
    (for [[id quantity] (sort-by (comp str key) (:fleet/entries fleet))
          :let [class (get classes id) selected? (= id selected-class)]]
      [:div {:data-fleet-class (str id)}
       [:h4 (or (:loadout/name class) (str "Missing class " id))]
       (edit-form fleet "class-set" {}
                  [[:input {:type "hidden" :name "class-id" :value (str id)}]
                   [:label "Quantity" [:input {:type "number" :name "quantity" :min 1 :step 1 :required true :value quantity}]]
                   [:button {:type "submit"} "Save quantity"]])
       [:form {:method "post" :action "/fleets/preview" :hx-post "/fleets/preview" :hx-target "#detail"
               :hx-sync "#workspace-navigation:drop" :hx-disabled-elt "find button"}
        [:input {:type "hidden" :name "id" :value (str (:fleet/id fleet))}]
        [:input {:type "hidden" :name "class-id" :value (str id)}]
        [:button {:type "submit" :disabled (nil? class) :aria-pressed (str (boolean selected?))} (if selected? "Previewing class" "Preview class")]]
       (edit-form fleet "class-remove" {}
                  [[:input {:type "hidden" :name "class-id" :value (str id)}]
                   [:button {:type "submit"} "Remove class"]])])
    (when (some #(= :running (:state %)) (vals (:prepared preview)))
      [:div {:data-viewport-pending true
             :hx-get (str "/fleets?poll=1&id=" (:fleet/id fleet) "&class-id=" selected-class)
             :hx-trigger "load delay:400ms" :hx-target "#detail" :hx-swap "innerHTML settle:0ms" :hx-sync "#detail:abort"}])
    (for [[id {:keys [state message]}] (:prepared preview) :when (= state :failed)]
      [:p.detail__error (str "Preview unavailable for " id ": " message)])
    [:h3 "Extra parts"]
    (edit-form fleet "extra-set" {:id "fleet-add-extra"}
               [[:label "Library part" [:select {:name "part-id" :required true}
                                        [:option {:value ""} "Choose a part"]
                                        (for [part (sort-by :part/id (vals parts)) :when (:part/present? part)]
                                          [:option {:value (:part/id part)} (:part/id part)])]]
                [:label "Quantity" [:input {:type "number" :name "quantity" :min 1 :step 1 :value 1 :required true}]]
                [:button {:type "submit"} "Add extra part"]])
    (for [[id quantity] (sort-by key (:fleet/extras fleet))]
      [:div {:data-fleet-extra id}
       [:h4 id]
       (edit-form fleet "extra-set" {}
                  [[:input {:type "hidden" :name "part-id" :value id}]
                   [:label "Quantity" [:input {:type "number" :name "quantity" :min 1 :step 1 :required true :value quantity}]]
                   [:button {:type "submit"} "Save quantity"]])
       (edit-form fleet "extra-remove" {}
                  [[:input {:type "hidden" :name "part-id" :value id}]
                   [:button {:type "submit"} "Remove extra part"]])])
    [:h3 "Variant preference"]
    [:ol#fleet-variants
     (for [[i variant] (map-indexed vector (:fleet/variant-preference fleet))]
       [:li {:data-fleet-variant (name variant)}
        [:span (name variant)]
        (for [[direction label boundary?] [["up" "Up" (zero? i)] ["down" "Down" (= i (dec (count (:fleet/variant-preference fleet))))]]]
          (edit-form fleet "variant" {}
                     [[:input {:type "hidden" :name "variant" :value (name variant)}]
                      [:input {:type "hidden" :name "direction" :value direction}]
                      [:button {:type "submit" :disabled boundary? :aria-label (str "Move fleet " (name variant) " " direction)} label]]))])]
    (edit-form fleet "default" {} [[:button {:type "submit"} "Use default"]])
    [:h3 "Required source files"]
    [:p {:data-fleet-ready (str (boolean (:ready? plan)))} (if (:ready? plan) "Ready to export" "Needs attention before export")]
    (for [{:keys [message part-id class-id]} (:errors plan)]
      [:p.detail__error {:data-fleet-plan-error true} (str message " " (or part-id class-id))])
    [:table [:thead [:tr [:th "Source file"] [:th "Variant"] [:th "Total quantity"]]]
     [:tbody (for [{:keys [part-id path variant quantity]} (:files plan)]
               [:tr {:data-fleet-file part-id}
                [:td path] [:td (name variant)] [:td {:data-fleet-quantity "true"} quantity]])]]
    export-controls
    (edit-form fleet "delete" {:hx-confirm (str "Delete fleet \"" (:fleet/name fleet) "\"? This removes its print list.")}
               [[:input {:type "hidden" :name "confirm" :value "yes"}]
                [:button {:type "submit"} "Delete fleet"]])]))
