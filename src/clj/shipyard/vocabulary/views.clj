(ns shipyard.vocabulary.views)

(defn picker []
  [:div.classification-picker
   [:label {:for "part-edit-value"} "Value"]
   [:div.classification-picker__control
    [:input#part-edit-value {:name "value" :list "part-bundle-values" :autocomplete "off"
                             :data-classification-input true :role "combobox" :aria-autocomplete "list"
                             :aria-expanded "false" :aria-controls "part-edit-options" :aria-describedby "part-edit-value-help"}]
    [:button {:type "button" :data-classification-toggle true :aria-label "Show classification values"
              :aria-controls "part-edit-options" :aria-expanded "false"} "▾"]]
   [:div#part-edit-options.classification-picker__options {:role "listbox" :aria-label "Classification values" :hidden true}]
   [:small#part-edit-value-help "Choose a value or type a new one. Apply to selected saves it."]])

(defn choices [values]
  [:div#classification-values
   (for [field [:bundle :class :role]]
     [:datalist {:id (str "part-" (name field) "-values")}
      (for [value (sort (get values field))] [:option {:value value}])])])
