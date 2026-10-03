(ns shipyard.settings.views
  "Settings forms; decisions and storage stay outside the rendered projection."
  (:require [shipyard.http.views :as views]))

(defn- classification-section [field label rows blocked?]
  [:section.settings-workspace__section
   [:h3 label]
   [:form.settings-workspace__add {:method "post" :action "/settings/classifications/add" :hx-post "/settings/classifications/add" :hx-target "#settings-workspace" :hx-swap "outerHTML"}
    [:input {:type "hidden" :name "field" :value (name field)}]
    [:label "New value" [:input {:name "value" :required true :maxlength "120" :disabled blocked?}]]
    [:button {:type "submit" :disabled blocked?} "Add"]]
   [:table.settings-workspace__values
    [:thead [:tr [:th "Value"] [:th "Uses"] [:th "Actions"]]]
    [:tbody
     (for [{:keys [value parts sockets builtin?]} rows]
       [:tr {:data-classification-field (name field) :data-classification-value value}
        [:td (if builtin? value
                 [:form.settings-workspace__rename {:method "post" :action "/settings/classifications/rename" :hx-post "/settings/classifications/rename" :hx-target "#settings-workspace" :hx-swap "outerHTML"}
                  [:input {:type "hidden" :name "field" :value (name field)}]
                  [:input {:type "hidden" :name "value" :value value}]
                  [:input {:name "new-value" :value value :required true :maxlength "120" :aria-label (str "Rename " label " " value) :disabled blocked?}]
                  [:button {:type "submit" :disabled blocked?} "Save"]])]
        [:td (str parts " parts" (when (pos? sockets) (str " · " sockets " sockets")))]
        [:td (if builtin? [:span.muted "Built-in"]
                 [:form {:method "post" :action "/settings/classifications/delete" :hx-post "/settings/classifications/delete" :hx-target "#settings-workspace" :hx-swap "outerHTML"}
                  [:input {:type "hidden" :name "field" :value (name field)}]
                  [:input {:type "hidden" :name "value" :value value}]
                  [:button {:type "submit" :disabled (or blocked? (pos? (+ parts sockets)))
                            :title (when (pos? (+ parts sockets)) "Only unused values can be deleted.")} "Delete"]])]])]]])

(defn panel [{:keys [root entries defaults blocked? error message draft]}]
  [:div#settings-workspace.settings-workspace {:data-workspace-filters "true"}
   [:h2 "Settings"]
   (when error [:p.detail__error {:role "alert"} error])
   (when message [:p {:role "status"} message])
   (when blocked? [:p "Finish or cancel the import to change the library or classification values."])
   [:section.settings-workspace__section
    [:h3 "Library folder"]
    [:p.settings__current (if root [:code root] [:span.muted "Not set"])]
    [:fieldset {:disabled blocked?} (views/settings-form {:id "settings" :root (get draft "root" root)})]]
   [:section.settings-workspace__section
    [:h3 "Pit and recess defaults"]
    [:p.muted "Used for new mount cuts. Existing cuts keep their saved dimensions. Values are in millimeters."]
    [:form#cut-defaults.settings-workspace__cuts {:method "post" :action "/settings/cuts" :hx-post "/settings/cuts" :hx-target "#settings-workspace" :hx-swap "outerHTML"}
     (for [[kind label dimensions] [[:pit "Pit" [[:depth "Depth"] [:diameter "Diameter"]]]
                                    [:recess "Recess" [[:depth "Depth"] [:border "Border"]]]]]
       [:fieldset [:legend label]
        (for [[dimension title] dimensions]
          [:label (str title " (mm)")
           [:input {:type "number" :name (str (name kind) "-" (name dimension))
                    :value (get draft (str (name kind) "-" (name dimension)) (get-in defaults [kind dimension])) :required true :min "0" :step "any"}]])])
     [:button {:type "submit"} "Save defaults"]]]
   [:p.muted "Classification values are shared across libraries. Renaming updates all part labels and socket acceptance lists. Only unused values can be deleted."]
   (for [[field label] [[:bundle "Faction"] [:class "Class"] [:role "Role"]]]
     (classification-section field label (get entries field) blocked?))])
