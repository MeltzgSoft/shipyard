(ns shipyard.vocabulary.views)

(defn choices [values]
  [:div#classification-values
   (for [field [:bundle :class :role]]
     [:datalist {:id (str "part-" (name field) "-values")}
      (for [value (sort (get values field))] [:option {:value value}])])])

(defn panel [values]
  [:details.classification-editor
   [:summary "Add faction, class or role"]
   [:form {:method "post" :action "/classifications" :hx-post "/classifications"
           :hx-target "#classification-status" :hx-swap "innerHTML" :hx-sync "#workspace-navigation:drop"
           :hx-disabled-elt "find button"}
    [:label "Field" [:select {:name "field"}
                     [:option {:value "bundle"} "Faction / bundle"]
                     [:option {:value "class"} "Class"] [:option {:value "role"} "Role"]]]
    [:label "New value" [:input {:name "value" :required true :maxlength 120}]]
    [:button {:type "submit"} "Add value"]]
   [:p#classification-status {:role "status"}]
   (choices values)])
