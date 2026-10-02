(ns shipyard.vocabulary.views)

(defn choices [values]
  [:div#classification-values
   (for [field [:bundle :class :role]]
     [:datalist {:id (str "part-" (name field) "-values")}
      (for [value (sort (get values field))] [:option {:value value}])])])
