(ns shipyard.assembly.views
  "Assembly rail and its hierarchical mount drawers."
  (:require [clojure.string :as str]
            [shipyard.assembly.model :as model]
            [shipyard.assembly.responses :as responses]
            [shipyard.assembly.scene :as scene]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.urls :as urls]))

(defn- form-attrs [action]
  {:method "post" :action action :hx-post action :hx-target "#detail"
   :hx-swap "innerHTML" :hx-sync "#detail:replace"})
(defn- hidden [name value] [:input {:type "hidden" :name name :value (str value)}])
(defn- slot-label [path]
  (str/join " / " (map (fn [[mount ordinal]] (str (name mount) " " (inc ordinal))) path)))

(defn- descendant-slots [slots id]
  (filter #(model/descendant? id (:id %)) slots))

(defn- selected-descendants [database descendant-slots]
  (keep (fn [{child-id :id assigned :assigned}]
          (when assigned
            {:id child-id :part (catalog/part database assigned)}))
        descendant-slots))

(defn- slot-view [database root revision available bundle class slots children
                  {:keys [id mount parent-role ancestors assigned]}]
  (let [candidates (filter #(available (:part/id %))
                           (model/candidates database root parent-role mount ancestors))
        current (when assigned (catalog/part database assigned))
        nested-slots (descendant-slots slots id)
        complete? (and (some? assigned) (every? :assigned nested-slots))
        selected-children (selected-descendants database nested-slots)
        details-attrs (cond-> {:data-slot (pr-str id)
                               :data-assigned (or assigned "")
                               :data-complete (str complete?)}
                        (not complete?) (assoc :open true))]
    [:div.assembly__slot-wrap
     {:style (str "--mount-color:" (:css (scene/color-for-slot id)))}
     [:details.assembly__slot details-attrs
      [:summary.assembly__mount-header
       [:span.assembly__mount-dot]
       [:span.assembly__mount-copy
        [:h3 (slot-label id)]
        [:span.assembly__mount-state (if assigned (or (:part/name current) "Selected") "Empty")]
        (when (seq selected-children)
          [:span.assembly__mount-subparts
           (for [{child-id :id part :part} selected-children]
             [:span.assembly__mount-subpart
              {:style (str "--subpart-color:" (:css (scene/color-for-slot child-id)))}
              [:span.assembly__mount-subpart-dot]
              [:span.assembly__mount-subpart-slot (slot-label child-id)]
              [:span.assembly__mount-subpart-name (or (:part/name part) "Selected")]])])]]
      (if (seq candidates)
        [:form.assembly__candidate-form (form-attrs "/assembly/assign")
         (hidden "revision" revision) (hidden "slot" (pr-str id))
         (hidden "bundle" bundle) (hidden "class" class)
         [:div.assembly__candidates
          (for [part candidates]
            [:button.assembly__candidate
             {:type "submit" :name "part-id" :value (:part/id part)
              :class (when (= assigned (:part/id part)) "assembly__candidate--selected")}
             [:span.assembly__candidate-name (:part/name part)]
             [:span.assembly__candidate-meta (name (:part/role-hint part))]])]]
        [:p.assembly__empty "No compatible parts in this bundle."])
      (when (seq children)
        [:div.assembly__nested children])]
     [:div.assembly__mount-actions
      [:form (form-attrs "/assembly/clear")
       (hidden "revision" revision) (hidden "slot" (pr-str id))
       (hidden "bundle" bundle) (hidden "class" class)
       [:button {:type "submit" :disabled (nil? assigned)} "Clear"]]]]))

(defn- slot-tree [database root revision available bundle class slots]
  (let [children-by-parent (group-by :parent slots)]
    (letfn [(render-slot [slot]
              (slot-view database root revision available bundle class slots
                         (map render-slot (get children-by-parent (:id slot)))
                         slot))]
      (map render-slot (get children-by-parent [])))))

(defn- assembly-filters [database selected-bundle selected-class]
  [:form.assembly__filters {:hx-get "/assembly" :hx-target "#detail" :hx-swap "innerHTML" :hx-trigger "change"}
   [:label "Bundle" [:select {:name "bundle"}
                     [:option {:value ""} "All bundles"]
                     (for [value (catalog/bundles database)] [:option {:value value :selected (= value selected-bundle)} value])]]
   [:label "Class" [:select {:name "class"}
                    [:option {:value ""} "All classes"]
                    (for [value (catalog/classes database)] [:option {:value value :selected (= value selected-class)} value])]]])

(defn- assembly-rail [{:keys [database hulls selected-bundle selected-class revision hull selected-hull root slots available]}]
  [:div#assembly-rail {:hx-swap-oob "innerHTML:#library"}
   (assembly-filters database selected-bundle selected-class)
   [:form.assembly__hull {:method "post" :action "/assembly/hull" :hx-post "/assembly/hull"
                          :hx-target "#detail" :hx-swap "innerHTML"}
    (hidden "revision" revision) (hidden "bundle" selected-bundle) (hidden "class" selected-class)
    [:label "Hull" [:select {:name "part-id" :required true :disabled (empty? hulls)}
                    [:option {:value ""} "Choose a hull…"]
                    (for [part hulls] [:option {:value (:part/id part) :selected (= (or hull selected-hull) (:part/id part))} (:part/name part)])]]
    [:button {:type "submit" :disabled (empty? hulls)} "Start assembly"]]
   [:p.assembly__rail-count (str (count hulls) " compatible hulls")]
   (when root
     [:div.assembly__rail-slots {:data-hull-id hull}
      (slot-tree database root revision available selected-bundle selected-class slots)])])

(defn panel [{:keys [database draft available prepared error selected-hull selected-bundle selected-class loadouts]}]
  (let [{:keys [revision hull assignments]} draft
        root (when hull (catalog/part database hull))
        derived (when hull (model/slots database hull assignments))
        slots (vec (:slots derived))
        hulls (->> (catalog/browse database {})
                   (remove model/root-error) (filter #(available (:part/id %)))
                   (filter #(or (nil? selected-bundle) (= selected-bundle (:part/bundle %))))
                   (filter #(or (nil? selected-class) (= selected-class (:part/class %)))))
        pending? (some #(= :running (:state %)) (vals prepared))]
    [:div.assembly-response
     [:section#assembly.assembly {:data-draft (pr-str draft)}
      [:header.assembly__header [:h2 "Assembly"]
       [:p (if root (str (:part/name root) " · " (count (filter :assigned slots)) " of " (count slots) " mounts filled")
               "Choose a hull, then fill its authored mount faces.")]]
      (when error [:p.detail__error {:role "alert"} (get responses/messages error (name error))])
      [:section.loadouts
       [:h3 "Saved loadouts"]
       [:form {:hx-post "/loadouts/save" :hx-target "#detail" :hx-swap "innerHTML"}
        [:label "Name " [:input {:name "name" :maxlength 160 :required true}]]
        [:button {:type "submit"} "Save loadout"]]
       (if (seq loadouts)
         [:ul (for [{:loadout/keys [id name]} loadouts]
                [:li [:strong name]
                 [:form {:hx-post "/loadouts/load" :hx-target "#detail" :hx-swap "innerHTML"}
                  [:input {:type "hidden" :name "id" :value (str id)}]
                  [:button {:type "submit"} "Load"]]
                 [:form {:hx-post "/loadouts/duplicate" :hx-target "#detail" :hx-swap "innerHTML"}
                  [:input {:type "hidden" :name "id" :value (str id)}]
                  [:input {:name "name" :placeholder "Copy name" :maxlength 160 :required true}]
                  [:button {:type "submit"} "Duplicate"]]])]
         [:p "No saved loadouts yet."])]
      (when (empty? hulls) [:p "No renderable hulls are available in this library."])
      (when hull
        [:div.assembly__body
         (when (empty? slots) [:p "This hull has no usable mount faces. Pick and save its plug or socket faces to add slots."])
         (for [{:keys [code slot part-id]} (:errors derived)]
           [:p.detail__error {:role "status"}
            (str (or (get responses/messages code)
                     (case code :incomplete-split "Pick this socket's face again to define its capacity split."
                           :invalid-socket "Reauthor the malformed socket." (name code)))
                 " " part-id (when (seq slot) (str " — " (slot-label slot))))])])
      (for [[id {:keys [state message]}] (sort-by key prepared) :when (not= :ready state)]
        [:div.assembly__preparation
         (if (= :failed state)
           [:p.detail__error "Could not prepare " id ": " message " "
            [:a {:href (str "/assembly?retry=" (urls/encode-id id)) :hx-get (str "/assembly?retry=" (urls/encode-id id)) :hx-target "#detail"} "Retry"]]
           [:p {:role "status"} "Preparing " id "…"])])
      (when pending? [:div {:hx-get "/assembly?poll=1" :hx-trigger "load delay:400ms" :hx-target "#detail" :hx-swap "innerHTML" :hx-sync "#detail:abort"}])]
     (assembly-rail {:database database :hulls hulls :selected-bundle selected-bundle :selected-class selected-class
                     :revision revision :hull hull :selected-hull selected-hull :root root :slots slots :available available})]))
