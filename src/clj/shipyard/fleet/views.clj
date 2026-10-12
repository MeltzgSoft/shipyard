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
                   [:td (reduce + 0 (vals (:fleet/entries fleet)))]
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

(defn editor [fleet error]
  (list
   [:section#library.panel {:hx-swap-oob "outerHTML" :data-fleet-view "editor"}]
   [:section.fleet-editor.ship-inspector {:data-fleet-id (str (:fleet/id fleet))}
    [:button (merge workspace/transition-attrs {:type "button" :data-workspace-transition true
                                                :hx-get "/workspace/fleets?table=1" :hx-target "#detail"}) "← Back to fleets"]
    [:h2 (:fleet/name fleet)]
    (when error [:p.detail__error {:role "alert"} error])
    [:p "Choose classes and extra parts to prepare a print bundle."]]))
