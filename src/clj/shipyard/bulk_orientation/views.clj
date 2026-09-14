(ns shipyard.bulk-orientation.views
  "Server-rendered selection table, preview grid, and save result."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.http.views :as http-views]
            [shipyard.part.orientation :as orientation]))

(defn- orientation-row [part]
  (let [[yaw pitch roll] (orientation/to-euler-degrees (:part/orientation part))
        renderable? (not (http-views/unrenderable-reason part))]
    [:label.bulk-orient__row
     {:class (when-not renderable? "bulk-orient__row--disabled")}
     [:input {:type "checkbox" :value (:part/id part) :disabled (not renderable?)
              :data-bulk-select "true"}]
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

(defn results [parts]
  [:div#bulk-orient-results.bulk-orient__results
   [:p.results__count (format "%d match%s" (count parts) (if (= 1 (count parts)) "" "es"))]
   [:div.bulk-orient__table {:role "group" :aria-label "Parts available for bulk orientation"}
    [:div.bulk-orient__columns {:aria-hidden "true"}
     [:span] [:span "Part"] [:span "Role"] [:span "Class"] [:span "Yaw"] [:span "Pitch"]
     [:span "Roll"] [:span "Orientation"]]
    (if (seq parts)
      (map orientation-row parts)
      [:p.bulk-orient__empty "No parts match these filters."])]])

(defn panel [facets]
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
   [:form.bulk-orient__selection
    {:hx-post "/orient/render" :hx-target "#bulk-orient" :hx-swap "innerHTML" :data-bulk-render "true"}
    [:input {:type "hidden" :name "part-ids" :value "[]" :data-bulk-ids "true"}]
    [:p [:strong {:data-bulk-count "true"} "0 selected"]]
    [:button {:type "submit" :disabled true :data-bulk-render-button "true"} "Render selection →"]]])

(defn grid [entries]
  (let [ids (mapv :part/id entries)
        preparing? (some #(= :preparing (:state %)) entries)]
    [:section.bulk-grid {:data-bulk-grid "true"}
     [:header.bulk-grid__toolbar
      [:button {:type "button" :data-bulk-back "true"} "← Back to table"]
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
      [:button {:type "button" :disabled preparing? :data-bulk-reset "true"} "Reset"]]
     (when preparing?
       [:span.bulk-grid__poll
        {:hx-post "/orient/render" :hx-trigger "load delay:400ms"
         :hx-target "#bulk-orient" :hx-swap "innerHTML"
         :hx-vals (json/write-str {"part-ids" (pr-str ids)})}])
     [:div.bulk-grid__cards
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
       [:input {:type "hidden" :name "orientations" :value "{}" :data-bulk-orientations "true"}]
       [:button {:type "submit" :disabled true :data-bulk-save-button "true"} "Save orientations"]]]]))

(defn save-result [{:keys [saved failed]}]
  [:span {:data-bulk-save-result (pr-str {:saved saved})}
   (if (seq failed)
     (str "Saved " (count saved) ". Failed: " (str/join ", " failed))
     (str "Saved " (count saved) " orientation" (when (not= 1 (count saved)) "s") "."))])
