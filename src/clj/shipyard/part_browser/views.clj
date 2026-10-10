(ns shipyard.part-browser.views
  "Shared metadata fields for individual parts and table drawers."
  (:require [shipyard.vocabulary.views :as vocabulary]
            [shipyard.workspace.views :as workspace-views]))

(defn variant-detail [key entry state message]
  [:div.detail.detail--variant {:data-variant-preview key}
   [:nav.detail__navigation {:aria-label "Part navigation"}
    [:button (merge workspace-views/transition-attrs
                    {:type "button" :data-part-back true :data-workspace-transition true
                     :hx-get "/workspace/browse?table=1" :hx-target "#detail"}) "← Back to table"]]
   [:header.detail__head
    [:h2.detail__name (:path entry)]]
   (case state
     :preparing [:div.detail__poll {:hx-get "/workspace/browse?resume=1" :hx-trigger "load delay:400ms"
                                    :hx-target "#detail" :hx-sync "this:drop" :hx-disabled-elt "unset"}
                 [:p.detail__status "Preparing preview…"]]
     :failed [:p.detail__error message]
     nil)])

(defn metadata-fields [prefix part]
  (list
   [:label.part-metadata__field "Name" [:input {:name "name" :value (:part/name part) :required true}]]
   (vocabulary/field-picker (str prefix "-bundle") "bundle" "Bundle / faction" (:part/bundle part))
   (vocabulary/field-picker (str prefix "-class") "class" "Class" (:part/class part))
   (vocabulary/field-picker (str prefix "-role") "role" "Role" (name (or (:part/role-hint part) :unknown)))))
