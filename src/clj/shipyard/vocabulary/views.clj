(ns shipyard.vocabulary.views)

(defn picker []
  [:div.classification-picker
   [:label {:for "part-edit-value"} "Value"]
   [:div.classification-picker__control
    [:input#part-edit-value {:name "value" :list "part-bundle-values" :autocomplete "off"
                             :data-classification-input true :role "combobox" :aria-autocomplete "list"
                             :aria-expanded "false" :aria-controls "part-edit-options"}]
    [:button {:type "button" :data-classification-toggle true :aria-label "Show classification values"
              :aria-controls "part-edit-options" :aria-expanded "false"} "▾"]]
   [:div#part-edit-options.classification-picker__options {:role "listbox" :aria-label "Classification values" :hidden true}]])

(defn field-picker [id field label value]
  [:div.classification-picker
   [:label {:for id} label]
   [:div.classification-picker__control
    [:input {:id id :name field :value value :list (str "part-" field "-values") :autocomplete "off"
             :data-classification-input true :data-classification-field field :role "combobox"
             :aria-autocomplete "list" :aria-expanded "false" :aria-controls (str id "-options")}]
    [:button {:type "button" :data-classification-toggle true :aria-label (str "Show " label " values")
              :aria-controls (str id "-options") :aria-expanded "false"} "▾"]]
   [:div.classification-picker__options {:id (str id "-options") :role "listbox" :aria-label label :hidden true}]])

(defn choices [values]
  [:div#classification-values
   (for [field [:bundle :class :role]]
     [:datalist {:id (str "part-" (name field) "-values")}
      (for [value (sort (get values field))] [:option {:value value}])])])
