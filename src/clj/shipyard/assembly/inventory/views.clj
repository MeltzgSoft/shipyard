(ns shipyard.assembly.inventory.views
  (:require [shipyard.http.pagination :as pagination]
            [shipyard.part-browser.table-views :as table]))

(defn results [parts quantities page]
  (let [window (pagination/window parts page)]
    [:div#extras-picker-results.inventory-picker__results
     [:input {:type "hidden" :name "page" :value (:page window)}]
     [:p.results__count (str (:total window) " matches")]
     [:div.inventory-picker__table {:role "group" :aria-label "Additional parts"}
      [:div.inventory-picker__columns
       (table/headings) (table/variant-headings) [:span "Quantity"]]
      (for [part (:items window) :let [id (:part/id part)]]
        [:div.inventory-picker__row {:data-inventory-part id}
         (table/cells part "#extras-picker-results") (table/variant-cells part)
         [:input {:type "number" :min 0 :max 1000000 :step 1 :required true
                  :name (str "quantity." id) :value (get quantities id 0)
                  :aria-label (str "Quantity for " (:part/name part)) :data-extra-quantity true}]])
      (when (empty? (:items window)) [:p "No parts match these filters."])]
     (pagination/controls window "/assembly/extras/filter" "#extras-picker-results" "#extras-picker-form"
                          {:method :post :sync "#extras-picker-form:queue all"
                           :disabled "#extras-picker [data-extra-quantity], #extras-picker button"})]))

(defn modal [picker facets parts]
  [:dialog.inventory-picker {:aria-labelledby "extras-picker-title" :data-inventory-modal true}
   [:form#extras-picker-form {:method "post" :action "/assembly/extras/add" :hx-post "/assembly/extras/add"
                              :hx-target "#extras-picker" :hx-sync "this:queue all"
                              :hx-include ".assembly__save input[name=name]"}
    [:h2#extras-picker-title "Additional inventory parts"]
    [:p "Set the quantities to include with this ship. Zero removes an additional part."]
    [:input {:type "hidden" :name "token" :value (:token picker)}]
    [:div.inventory-picker__filters.filters
     {:hx-post "/assembly/extras/filter" :hx-target "#extras-picker-results" :hx-swap "outerHTML"
      :hx-include "#extras-picker-form" :hx-sync "#extras-picker-form:queue all"
      :hx-vals "{\"page\":\"1\"}"
      :hx-disabled-elt "#extras-picker [data-extra-quantity], #extras-picker button"
      :hx-on:keydown "if(event.key==='Enter'){event.preventDefault();this.dispatchEvent(new Event('search',{bubbles:true}));}"
      :hx-trigger "change, search, keyup changed delay:300ms"}
     (table/filters facets (:filters picker))]
    [:div#extras-picker-status {:role "status"}]
    (results parts (:quantities picker) 1)
    [:footer.inventory-picker__actions
     [:button {:type "submit"} "Add"]
     [:button {:type "button" :data-inventory-cancel true
               :hx-post "/assembly/extras/cancel" :hx-include "#extras-picker-form"
               :hx-target "#extras-picker" :hx-sync "#extras-picker-form:queue all"} "Cancel"]]]])
