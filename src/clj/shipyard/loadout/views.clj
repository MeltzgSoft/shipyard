(ns shipyard.loadout.views
  "Class table, expandable named hulls and server-rendered navigation forms."
  (:require [clojure.string :as str]
            [shipyard.workspace.views :as workspace-views]
            [shipyard.http.pagination :as pagination]
            [shipyard.thumbnail.views :as thumbnails]))

(defn- action [id action label]
  [:form.ship-card__action
   (merge workspace-views/transition-attrs
          {:method "post" :action (str "/ships/" action) :hx-post (str "/ships/" action)
           :hx-include "#ship-filters, #ship-table-position, [data-ship-page]" :hx-target "#detail"})
   [:input {:type "hidden" :name "id" :value (str id)}]
   [:button {:type "submit" :data-workspace-transition "true"} label]])

(defn- open-row [kind id]
  (str "if(!event.target.closest('button,input,select,summary,form')){var f=document.getElementById('ship-open-form');"
       "f.elements.id.value='" id "';f.elements.kind.value='" kind "';htmx.trigger(f,'submit');}"))

(defn- thumbnail [kind id]
  [:span.ship-thumbnail (thumbnails/lazy-attrs (str "/ship-thumbnails/" kind "/" id) "#ship-results" true)
   "…"])

(defn named-delete-form [ship return-view form-attrs button-attrs label]
  [:form (merge {:method "post" :action "/ships/paint/delete" :hx-post "/ships/paint/delete" :hx-target "#detail"
                 :hx-confirm (str "Delete named ship “" (:ship/name ship) "” and its custom paint? Its class and scheme will be kept.")}
                form-attrs)
   [:input {:type "hidden" :name "id" :value (str (:ship/id ship))}]
   [:input {:type "hidden" :name "confirmed" :value "true"}]
   (when return-view [:input {:type "hidden" :name "return" :value return-view}])
   [:button (merge {:type "submit"} button-attrs) label]])

(defn named-actions [ship return-view]
  [:div.named-ship-actions
   [:form (merge workspace-views/transition-attrs
                 {:method "post" :action "/ships/open" :hx-post "/ships/open" :hx-target "#detail"
                  :hx-include "#ship-filters, #ship-table-position, [data-ship-page]"})
    [:input {:type "hidden" :name "kind" :value "ship"}]
    [:input {:type "hidden" :name "id" :value (str (:ship/id ship))}]
    [:button {:type "submit" :data-workspace-transition "true" :aria-label (str "Edit ship " (:ship/name ship))} "Edit"]]
   (named-delete-form ship return-view
                      (assoc workspace-views/transition-attrs :hx-include "#ship-filters, #ship-table-position, [data-ship-page]")
                      {:data-workspace-transition "true" :aria-label (str "Delete ship " (:ship/name ship))} "Delete")])

(defn customize-table [ships classes schemes selected page]
  (let [classes (into {} (map (juxt :loadout/id :loadout/name)) classes)
        schemes (into {} (map (juxt :scheme/id :scheme/name)) schemes)
        window (pagination/window (sort-by :ship/name ships) page)]
    [:details#customize-ships.customize-ships {:open true} [:summary "Named ships"]
     (pagination/controls window "/ships/customize/ships" "#customize-ships" "")
     (if (seq ships)
       [:table [:thead [:tr [:th "Ship / class"] [:th "Scheme"] [:th "Actions"]]]
        [:tbody (for [ship (:items window)]
                  [:tr {:data-ship-id (str (:ship/id ship)) :aria-current (when (= selected (:ship/id ship)) "true")}
                   [:td [:strong (:ship/name ship)] [:small (get classes (:ship/class ship) "Class unavailable")]]
                   [:td (if (:ship/scheme ship) (get schemes (:ship/scheme ship) "Scheme unavailable") "No scheme")]
                   [:td (named-actions ship "customize")]])]]
       [:p.ship-table__empty "No named ships yet. Create one below."])]))

(defn named-rows
  ([entry page q] (named-rows entry page q false))
  ([{:keys [loadout ships bundle class]} page q chunk?]
   (let [q (str/lower-case (or q ""))
        ;; A class-name match shows all its hulls; otherwise show matching hull names.
         ships (if (str/includes? (str/lower-case (:loadout/name loadout)) q) ships
                   (filter #(str/includes? (str/lower-case (:ship/name %)) q) ships))
         window (pagination/batch-window (sort-by (juxt :ship/name :ship/id) ships) page chunk?)
         id (:loadout/id loadout)
         content
         (list
          (if (seq ships)
            (for [ship (:items window)]
              [:div.ship-table__row.ship-table__named {:tabindex 0 :role "button" :data-ship-id (str (:ship/id ship))
                                                       :aria-label (str "Open ship " (:ship/name ship))
                                                       :hx-on:dblclick (open-row "ship" (:ship/id ship))
                                                       :hx-on:keydown (str "if(event.key==='Enter' && event.target===this){event.preventDefault();" (open-row "ship" (:ship/id ship)) "}")}
               (thumbnail "ship" (:ship/id ship))
               [:span.ship-table__name (:ship/name ship)] [:span bundle] [:span class] [:span "Custom hull"]
               (named-actions ship "table")])
            [:p.ship-table__empty "No named ships yet. Open this class and choose Customize to create one."])
          (pagination/more window (str "/ships/hulls/" id) "#ship-results" "#ship-filters"))]
     (if chunk? content [:div {:id (str "named-ships-" id)} content]))))

(defn results
  ([entries filters] (results entries filters nil nil))
  ([entries filters selected-class selected-ship] (results entries filters selected-class selected-ship false))
  ([entries filters _selected-class _selected-ship chunk?]
   (let [content
         (let [q (str/lower-case (or (get filters "q") ""))
               expanded (set (str/split (or (get filters "expanded") "") #","))
               matches (filter #(and (or (not (seq (get filters "bundle"))) (= (get filters "bundle") (:bundle %)))
                                     (or (not (seq (get filters "class"))) (= (get filters "class") (:class %)))
                                     (or (str/includes? (str/lower-case (get-in % [:loadout :loadout/name])) q)
                                         (some (fn [ship] (str/includes? (str/lower-case (:ship/name ship)) q)) (:ships %)))) entries)
               window (pagination/batch-window matches (get filters "page") chunk?)]
           (list
            (if (seq matches)
              (for [[n batch] (map-indexed vector (partition-all pagination/page-size (:items window)))]
                [:div.list-chunk.ship-list-chunk {:data-list-page (if chunk? (:page window) (inc n))}
                 (for [{:keys [loadout bundle class missing? empty-mounts ships] :as entry} batch
                       :let [{:loadout/keys [id name]} loadout
                             open? (or (contains? expanded (str id))
                                       (and (seq q) (some #(str/includes? (str/lower-case (:ship/name %)) q) ships)))]]
                   [:article.ship-card.ship-table__class {:data-loadout-id (str id)}
                    [:div.ship-table__row {:tabindex 0 :role "button" :aria-label (str "Open class " name)
                                           :hx-on:dblclick (open-row "class" id)
                                           :hx-on:keydown (str "if(event.key==='Enter' && event.target===this){event.preventDefault();" (open-row "class" id) "}")}
                     (thumbnail "class" id)
                     [:strong.ship-table__name name] [:span bundle] [:span class]
                     [:span (cond missing? [:span.detail__error "Hull missing"]
                                  (pos? (or empty-mounts 0)) [:span.ship-card__empty-mounts (str empty-mounts " empty mount" (when (not= 1 empty-mounts) "s"))]
                                  :else "Ready")]
                     [:div.ship-table__actions (action id "edit" "Edit") (action id "duplicate" "Duplicate")
                      (assoc-in (action id "delete" "Delete") [1 :hx-confirm]
                                (str "Delete ship class “" name "”? Named ships retain their paint, but need this class restored before editing."))]]
                    [:details.ship-card__ships {:open open?
                                                :hx-on:toggle "document.querySelector('#ship-table-position input[name=expanded]').value=[...document.querySelectorAll('.ship-card:has(details[open])')].map(e=>e.dataset.loadoutId).join(',')"}
                     [:summary (str (count ships) " named ships")]
                     (if open?
                       (named-rows entry (get-in filters ["hull-pages" (str id)]) q)
                       [:div {:id (str "named-ships-" id)
                              :hx-get (str "/ships/hulls/" id) :hx-target "this" :hx-swap "outerHTML"
                              :hx-include "#ship-filters" :hx-trigger "intersect once root:#ship-results"}
                        "Loading named ships…"])]])])
              [:p.ship-table__empty "No ship classes match."])
            (pagination/more window "/ships" "#ship-results" "#ship-filters")))]
     (if chunk? content
         [:div#ship-results.ship-table__results
          {:onscroll "document.querySelector('#ship-table-position input[name=table-scroll]').value=this.scrollTop"
           :data-scroll-top (or (get filters "table-scroll") "0")
           :hx-on--load "if(event.target===this){this.scrollTop=Number(this.dataset.scrollTop||0)}"}
          [:input {:type "hidden" :name "page" :value (or (get filters "page") "1") :data-ship-page true}]
          [:div.ship-table__columns [:span "Preview"] [:span "Ship class / named ship"] [:span "Bundle / faction"] [:span "Class"] [:span "Status"] [:span "Actions"]]
          content]))))

(defn cards
  ([entries filters] (cards entries filters nil))
  ([entries filters draft]
   [:section#library.panel.ship-table {:hx-swap-oob "outerHTML" :data-ship-view "table"
                                       :hx-on--config-request "var r=this.querySelector('#ship-results');if(!event.detail.elt.closest('.list-more,#ship-filters')){event.detail.parameters['table-scroll']=String(r.scrollTop)}"
                                       :hx-on--load "if(event.target===this){var r=this.querySelector('#ship-results');r.scrollTop=Number(r.dataset.scrollTop||0)}"}
    [:header.bulk-orient__head
     [:h2 "Ship Browser"]
     [:form.ship-table__new (merge workspace-views/transition-attrs
                                   {:method "post" :action "/ships/new" :hx-post "/ships/new" :hx-target "#detail"
                                    :hx-include "#ship-filters, #ship-table-position, [data-ship-page]"})
      [:button {:type "submit" :data-workspace-transition "true"} "New class"]]
     [:button (merge workspace-views/transition-attrs
                     {:type "button" :data-workspace-transition "true" :data-ship-new "true"
                      :disabled (nil? (:hull draft))
                      :hx-get "/ships/tab/assembly" :hx-target "#detail" :hx-include "#ship-filters, #ship-table-position, [data-ship-page]"}) "Resume assembly"]]
    (workspace-views/filter-sidebar
     "ship-filter-sidebar"
     [:form#ship-filters.filters {:data-workspace-filters "true" :hx-get "/ships" :hx-target "#ship-results" :hx-swap "outerHTML"
                                  :hx-trigger "change[target.tagName === 'SELECT'], search, keyup changed delay:300ms"
                                  :hx-include "#ship-table-position" :hx-vals "{\"page\":\"1\",\"table-scroll\":\"0\"}" :hx-sync "this:replace"}
      (for [[field label] [["bundle" "Bundle / faction"] ["class" "Class"]]]
        [:label label [:select {:name field}
                       [:option {:value ""} (str "All " (str/lower-case label))]
                       (for [value (sort (distinct (keep (keyword field) entries)))]
                         [:option {:value value :selected (= value (get filters field))} value])]])
      [:label "Name" [:input {:type "search" :name "q" :value (get filters "q") :placeholder "Class or ship name"}]]]
     [[:form#ship-table-position
       [:input {:type "hidden" :name "table-scroll" :value (or (get filters "table-scroll") "0")}]
       [:input {:type "hidden" :name "expanded" :value (or (get filters "expanded") "")}]]
    ;; Stable request origin survives an in-flight filter replacing the clicked row.
      [:form#ship-open-form (merge workspace-views/transition-attrs
                                   {:hidden true :method "post" :action "/ships/open" :hx-post "/ships/open"
                                    :hx-target "#detail" :hx-include "#ship-filters, #ship-table-position, [data-ship-page]"})
       [:input {:type "hidden" :name "kind"}] [:input {:type "hidden" :name "id"}]]
      (pagination/progress)
      (results entries filters)])]))
