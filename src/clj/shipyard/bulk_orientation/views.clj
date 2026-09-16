(ns shipyard.bulk-orientation.views
  "Server-rendered selection table, preview grid, and save result."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.http.views :as http-views]
            [shipyard.part.orientation :as orientation]
            [shipyard.workspace.views :as workspace-views]))

(defn- orientation-row [selected part]
  (let [[yaw pitch roll] (orientation/to-euler-degrees (:part/orientation part))
        renderable? (not (http-views/unrenderable-reason part))]
    [:label.bulk-orient__row
     {:class (when-not renderable? "bulk-orient__row--disabled")}
     [:input {:type "checkbox" :value (:part/id part) :disabled (not renderable?)
              :data-bulk-select "true" :name "selected" :checked (contains? selected (:part/id part))}]
     [:span.bulk-orient__part (:part/name part)]
     [:span.bulk-orient__role (name (or (:part/role-hint part) :unknown))]
     [:span.bulk-orient__class (or (:part/class part) "—")]
     [:code (if (bulk/saved? part) yaw "—")]
     [:code (if (bulk/saved? part) pitch "—")]
     [:code (if (bulk/saved? part) roll "—")]
     [:span {:class (if (bulk/saved? part) "bulk-orient__saved" "bulk-orient__unset")}
      (cond
        (not renderable?) "No preview"
        (bulk/saved? part) "Saved"
        :else "Unset")]]))

(defn results
  ([parts] (results parts #{}))
  ([parts selected]
   [:div#bulk-orient-results.bulk-orient__results
    [:p.results__count (format "%d match%s" (count parts) (if (= 1 (count parts)) "" "es"))]
    [:form.bulk-orient__table {:role "group" :aria-label "Parts available for bulk orientation"
                               :hx-post "/orient/selection" :hx-trigger "change" :hx-target "#bulk-selection"
                               :hx-swap "outerHTML" :hx-sync "this:replace"
                               :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition]"}
     [:input {:type "hidden" :name "visible" :value (pr-str (mapv :part/id parts))}]
     [:div.bulk-orient__columns {:aria-hidden "true"}
      [:span] [:span "Part"] [:span "Role"] [:span "Class"] [:span "Yaw"] [:span "Pitch"]
      [:span "Roll"] [:span "Orientation"]]
     (if (seq parts)
       (map (partial orientation-row selected) parts)
       [:p.bulk-orient__empty "No parts match these filters."])]]))

(defn selection-form [selection]
  (let [ids (bulk/selected-ids selection)]
    [:form#bulk-selection.bulk-orient__selection
     (merge workspace-views/transition-attrs {:hx-post "/orient/render" :hx-target "#detail" :hx-swap "innerHTML settle:0ms"
                                              :data-bulk-render "true" :hx-include "#bulk-orient-filters"})
     [:input {:type "hidden" :name "part-ids" :value (or selection "[]") :data-bulk-ids "true"}]
     [:p [:strong {:data-bulk-count "true"} (str (count ids) " selected")]]
     [:button {:type "submit" :disabled (empty? ids) :data-bulk-render-button "true" :data-workspace-transition "true"} "Render selection →"]]))

(defn panel
  ([facets] (panel facets nil))
  ([facets selection]
   [:section#library.panel.bulk-orient
    [:header.bulk-orient__head [:h2 "Bulk orientation"] [:p "Filter a set, then render it together."]]
    [:form#bulk-orient-filters.filters
     {:hx-get "/orient/parts" :hx-target "#bulk-orient-results" :hx-swap "outerHTML"
      :hx-trigger "load, change, search, keyup changed delay:300ms"}
     [:label.filters__field "Bundle" [:select {:name "bundle"} (http-views/options "All bundles" (:bundles facets))]]
     [:label.filters__field "Class" [:select {:name "class"} (http-views/options "All classes" (:classes facets))]]
     [:label.filters__field "Role" [:select {:name "role"} (http-views/options "All roles" (map name (:roles facets)))]]
     [:label.filters__field "Orientation" [:select {:name "orientation"}
                                           [:option {:value "all"} "Any orientation"]
                                           [:option {:value "unset"} "Orientation unset"]
                                           [:option {:value "saved"} "Orientation saved"]]]
     [:label.filters__field "Name" [:input {:type "search" :name "q" :placeholder "Search names"}]]]
    [:div#bulk-orient-results.bulk-orient__results [:p.muted "Loading parts…"]]
    (selection-form selection)]))

(defn grid [entries]
  (let [ids (mapv :part/id entries)
        preparing? (some #(= :preparing (:state %)) entries)]
    [:section.bulk-grid {:data-bulk-grid "true"}
     [:header.bulk-grid__toolbar
      [:button (merge workspace-views/transition-attrs {:type "button" :data-bulk-back "true" :data-workspace-transition "true"
                                                        :hx-get "/workspace/orient?table=1" :hx-target "#detail" :hx-swap "innerHTML settle:0ms"}) "← Back to table"]
      [:span#bulk-grid-controls.bulk-grid__controls
       [:strong [:span {:data-bulk-grid-count "true"} (str (count entries) " selected")]]
       [:span.bulk-grid__divider]
       (for [[axis label] [["x" "Pitch"] ["y" "Yaw"] ["z" "Roll"]]]
         [:span.bulk-grid__control [:span {:class (str "bulk-grid__axis bulk-grid__axis--" axis)} label]
          [:button {:type "button" :disabled preparing? :data-bulk-rotate "true" :data-axis axis :data-direction "-1"} "−"]
          [:input {:type "number" :disabled preparing? :step "1" :inputmode "decimal"
                   :placeholder "degrees" :aria-label (str "Set " label " degrees for selection")
                   :data-bulk-angle "true" :data-axis axis}]
          [:button {:type "button" :disabled preparing? :data-bulk-rotate "true" :data-axis axis :data-direction "1"} "+"]])
       [:span.bulk-grid__steps {:aria-label "Rotation step"}
        (for [step [1 15 90]]
          [:button {:type "button" :data-bulk-step step :aria-pressed (= 90 step)} (str step "°")])]
       [:button {:type "button" :disabled preparing? :data-bulk-copy "true"} "Copy first"]
       [:button {:type "button" :disabled preparing? :data-bulk-reset "true"} "Reset"]]]
     [:div.bulk-grid__cards
      (when preparing?
        [:span.bulk-grid__poll
         {:hidden true :hx-post "/orient/render" :hx-trigger "load delay:400ms"
          :hx-target ".bulk-grid__cards" :hx-swap "outerHTML"
          :hx-select ".bulk-grid__cards" :hx-select-oob "#bulk-grid-controls"
          :hx-vals (json/write-str {"part-ids" (pr-str ids) "poll" "1"})}])
      (for [{:part/keys [id name orientation] :keys [mesh-url mesh-key state message]} entries]
        [:article.bulk-grid__card
         (cond-> {:data-bulk-part id :data-orientation (pr-str orientation)}
           mesh-url (assoc :data-mesh-url mesh-url :data-mesh-key mesh-key)
           (= state :preparing) (assoc :data-preparing "true"))
         [:div.bulk-grid__preview {:data-bulk-preview "true"}
          [:span.bulk-grid__card-state (when-not mesh-url (or message "Preparing…"))]]
         [:p name]])]
     [:footer.bulk-grid__footer
      [:span#bulk-orient-status "Preview changes are not saved."]
      [:form {:hx-post "/orient/save" :hx-target "#bulk-orient-status" :hx-swap "innerHTML"
              :data-bulk-save "true"}
       [:input {:type "hidden" :name "request" :value "0"}]
       [:input {:type "hidden" :name "orientations" :value "{}" :data-bulk-orientations "true"}]
       [:button {:type "submit" :disabled true :data-bulk-save-button "true"} "Save orientations"]]]]))

(defn save-result [{:keys [saved failed request activation]}]
  [:span {:data-bulk-save-result (pr-str {:saved saved :request request :activation activation})}
   (if (seq failed)
     (str "Saved " (count saved) ". Failed: " (str/join ", " failed))
     (str "Saved " (count saved) " orientation" (when (not= 1 (count saved)) "s") "."))])
