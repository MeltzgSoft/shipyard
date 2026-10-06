(ns shipyard.vocabulary.views)

(defn- picker-control [{:keys [id field label value list-id options-id options-label toggle-label]}]
  [:div.classification-picker
   [:label {:for id} label]
   [:div.classification-picker__control
    [:input (cond-> {:id id :name field :value value :list list-id :autocomplete "off"
                     :data-classification-input true :role "combobox" :aria-autocomplete "list"
                     :aria-expanded "false" :aria-controls options-id}
              (not= field "value") (assoc :data-classification-field field))]
    [:button {:type "button" :data-classification-toggle true :aria-label toggle-label
              :aria-controls options-id :aria-expanded "false"} "▾"]]
   [:div.classification-picker__options {:id options-id :role "listbox" :aria-label options-label :hidden true}]])

(defn picker []
  (picker-control {:id "part-edit-value" :field "value" :label "Value" :list-id "part-bundle-values"
                   :options-id "part-edit-options" :options-label "Classification values"
                   :toggle-label "Show classification values"}))

(defn field-picker [id field label value]
  (picker-control {:id id :field field :label label :value value :list-id (str "part-" field "-values")
                   :options-id (str id "-options") :options-label label :toggle-label (str "Show " label " values")}))

(defn choices [values]
  [:div#classification-values
   (for [field [:bundle :class :role]]
     [:datalist {:id (str "part-" (name field) "-values")}
      (for [value (sort (get values field))] [:option {:value value}])])])
