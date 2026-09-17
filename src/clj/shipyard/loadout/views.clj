(ns shipyard.loadout.views
  "Saved-ship cards and a path-identified assembly inspector."
  (:require [clojure.string :as str]
            [shipyard.assembly.scene :as scene]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.urls :as urls]
            [shipyard.loadout.model :as model]
            [shipyard.workspace.views :as workspace-views]))

(defn- action [id action label]
  (let [select? (= "preview" action)]
    [:form (merge (if select? {:hx-sync "#detail:replace"} workspace-views/transition-attrs)
                  {:class (if select? "ship-card__select" "ship-card__action")
                   :hx-post (str "/ships/" action) :hx-target "#detail" :hx-swap "innerHTML settle:0ms"})
     [:input {:type "hidden" :name "id" :value (str id)}]
     (if select?
       [:h3 [:button.ship-card__load {:type "submit" :aria-label (str "Load " label)} label]]
       [:button {:type "submit" :data-workspace-transition "true"} label])]))

(defn results [entries filters]
  [:div#ship-results.ship-cards
   (let [matches (filter #(and (or (not (seq (get filters "bundle"))) (= (get filters "bundle") (:bundle %)))
                               (or (not (seq (get filters "class"))) (= (get filters "class") (:class %)))) entries)]
     (if (seq matches)
       (for [{:keys [loadout bundle class missing? empty-mounts]} matches
             :let [{:loadout/keys [id name]} loadout]]
         [:article.ship-card {:data-loadout-id (str id)}
          (action id "preview" name) [:p (str/join " · " (remove nil? [bundle class]))]
          (when missing? [:p.detail__error "Root hull missing from the library."])
          (when (pos? (or empty-mounts 0))
            [:p [:span.ship-card__empty-mounts
                 (str empty-mounts (if (= 1 empty-mounts) " empty mount" " empty mounts"))]])
          (action id "edit" "Edit") (action id "duplicate" "Duplicate")])
       [:p "No saved ships match."]))])

(defn cards [entries filters]
  [:section#library.panel {:hx-swap-oob "outerHTML"}
   [:h2.panel__title "Saved ships"]
   [:form#ship-filters.filters {:hx-get "/ships" :hx-target "#ship-results" :hx-swap "outerHTML"
                                :hx-trigger "change" :hx-sync "this:replace"}
    (for [[field label] [["bundle" "Bundle / faction"] ["class" "Class"]]]
      [:label label
       [:select {:name field}
        [:option {:value ""} (str "All " (str/lower-case label))]
        (for [value (sort (distinct (keep (keyword field) entries)))]
          [:option {:value value :selected (= value (get filters field))} value])]])]
   (results entries filters)])

(defn inspector [database draft prepared error]
  [:section.ship-inspector
   (when error [:p.detail__error {:role "alert"} error])
   (if (:hull draft)
     [:div
      [:h2 (:name draft)]
      [:p "Saved assembly · preview"]
      [:ul.ship-tree
       (for [[path id] (model/part-tree draft)
             :let [part (catalog/part database id) color (:css (scene/color-for-slot path))]]
         [:li {:data-ship-slot (pr-str path) :data-part-id id
               :style (str "--slot-color:" color ";padding-left:" (* 10 (count path)) "px")}
          [:span.ship-tree__color {:aria-label (str "Mount color " color)}]
          [:span (or (:part/name part) id)]
          [:small (if (empty? path) "Hull" (str/join " / " (map (fn [[mount ordinal]] (str (name mount) " " (inc ordinal))) path)))]])]
      (for [[id status] prepared :when (not= :ready (:state status))]
        [:p {:role "status"} (if (= :failed (:state status))
                               (str "Could not prepare " id ". " (:message status))
                               (str "Preparing " id "…"))
         (when (= :failed (:state status))
           [:a {:hx-get (str "/ships?retry=" (urls/encode-id id)) :hx-target "#detail" :href "/ships"} "Retry"])])
      (when (some #(= :running (:state %)) (vals prepared))
        [:span {:hx-get "/ships?poll=1" :hx-trigger "load delay:400ms" :hx-target "#detail" :hx-sync "#detail:abort"}])]
     [:p "Select a saved ship to preview it."])])
