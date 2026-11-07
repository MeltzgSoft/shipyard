(ns shipyard.export.views
  (:require [shipyard.export.transforms :as transforms]))

(defn checkbox [kind id label selection]
  [:input {:type "checkbox" :aria-label (str "Export " label) :data-export-id (str id)
           :checked (contains? (set selection) [kind id])
           :hx-include "#export-controls input" :hx-sync "#workspace-navigation:queue all"
           :hx-post "/ships/export/selection" :hx-trigger "change" :hx-target "#detail"
           :hx-vals (str "js:{kind:'" (name kind) "',id:'" id "',selected:event.target.checked?'true':'false'}")}])

(defn panel [{:keys [selection priority directory diameter]} files error completed]
  [:section#export-controls
   [:button {:type "button" :hx-get "/ships" :hx-target "#detail"} "Close export"]
   [:button {:type "button" :hx-post "/ships/export/clear" :hx-target "#detail"} "Clear selection"]
   [:h2 "Export selected ships"]
   [:p (str (count selection) " selected")]
   [:form {:method "post" :action "/ships/export" :hx-post "/ships/export" :hx-sync "#workspace-navigation:queue all" :hx-target "#detail"}
    [:label "Export directory" [:input {:name "directory" :value (or directory "") :required true}]]
    [:label "Stand socket diameter (mm)" [:input {:type "number" :name "diameter" :value (or diameter "") :min "0.1" :max "100" :step "any"}]]
    [:p "Leave diameter blank to preserve the original sockets."]
    [:h3 "Variant priority"]
    (for [[i variant] (map-indexed vector (or priority transforms/default-priority))]
      [:div [:span (name variant) " "]
       [:button {:type "submit" :formnovalidate true :name "move" :value (str (name variant) ":up") :disabled (zero? i)} "Up"]
       [:button {:type "submit" :formnovalidate true :name "move" :value (str (name variant) ":down") :disabled (= i 2)} "Down"]])
    (when (seq files)
      [:table [:thead [:tr [:th "Ship"] [:th "Part"] [:th "Variant"] [:th "Quantity"] [:th "Preview"]]]
       [:tbody (for [file files]
                 [:tr [:td (:ship-name file)] [:td (:part-id file)] [:td (name (:variant file))] [:td (str (:quantity file))]
                  [:td [:button {:type "submit" :formnovalidate true :name "preview" :value (:part-id file)} "Preview model"]]])]])
    [:button {:type "submit" :formnovalidate true :name "action" :value "review"} "Review choices"]
    [:button {:type "submit" :name "action" :value "export" :disabled (or (empty? files) (some? error))} "Export ZIP"]]
   (when error [:p.detail__error {:role "alert"} error])
   (when completed [:p {:role "status"} "Export complete: " [:a {:href "/ships/export/download" :download true} completed]])])
