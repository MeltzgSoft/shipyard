(ns shipyard.assembly.views
  "Server-rendered hull and slot choices; the viewport owns no draft or compatibility state."
  (:require [clojure.string :as str]
            [shipyard.assembly.model :as model]
            [shipyard.assembly.responses :as responses]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.urls :as urls]))

(defn- form-attrs [action]
  {:method "post" :action action :hx-post action :hx-target "#detail"
   :hx-swap "innerHTML" :hx-sync "#detail:replace"})

(defn- hidden [name value] [:input {:type "hidden" :name name :value (str value)}])

(defn- slot-label [path]
  (str/join " / " (map (fn [[mount ordinal]] (str (name mount) " " (inc ordinal))) path)))

(defn- slot-view [database root revision available {:keys [id socket ancestors assigned]}]
  (let [candidates (filter #(available (:part/id %)) (model/candidates database root socket ancestors))
        current (when assigned (catalog/part database assigned))]
    [:article.assembly__slot {:data-slot (pr-str id)}
     [:h3 (slot-label id)]
     [:p.assembly__current (if assigned (str "Selected: " (or (:part/name current) assigned)) "Empty")]
     (if (seq candidates)
       [:form (form-attrs "/assembly/assign")
        (hidden "revision" revision) (hidden "slot" (pr-str id))
        [:label "Compatible part"
         [:select {:name "part-id" :aria-label (str "Part for " (slot-label id))}
          (for [part candidates]
            [:option {:value (:part/id part) :selected (= assigned (:part/id part))} (:part/name part)])]]
        [:button {:type "submit"} (if assigned "Replace" "Assign")]]
       [:p "No compatible parts. Author a role and one plug on an unsupported part in this bundle and class."])
     (when assigned
       [:form (form-attrs "/assembly/clear")
        (hidden "revision" revision) (hidden "slot" (pr-str id))
        [:button {:type "submit"} "Clear"]])]))

(defn panel [{:keys [database draft available prepared error selected-hull]}]
  (let [{:keys [revision hull assignments]} draft
        root (when hull (catalog/part database hull))
        derived (when hull (model/slots database hull assignments))
        hulls (->> (catalog/browse database {})
                   (remove model/root-error)
                   (filter #(available (:part/id %))))
        pending? (some #(= :running (:state %)) (vals prepared))]
    [:section#assembly.assembly {:data-draft (pr-str draft)}
     [:header.assembly__header
      [:h2 "Assembly"]
      [:p "Choose a hull, then fill its authored sockets. This draft lasts until the application stops."]]
     (when error [:p.detail__error {:role "alert"} (get responses/messages error (name error))])
     [:form.assembly__hull (form-attrs "/assembly/hull")
      (hidden "revision" revision)
      [:label "Hull"
       [:select {:name "part-id" :required true :disabled (empty? hulls)}
        (for [part hulls]
          [:option {:value (:part/id part) :selected (= (or selected-hull hull) (:part/id part))}
           (str (:part/name part) " — " (:part/bundle part))])]]
      [:button {:type "submit" :disabled (empty? hulls)} "Start assembly"]]
     (when (empty? hulls) [:p "No authored renderable hulls. Open a hull in the library and save its role first."])
     (when hull
       [:div
        [:p [:strong "Hull: "] (or (:part/name root) hull)]
        [:div.assembly__actions
         [:a {:href (urls/part-url hull) :hx-get (urls/part-url hull) :hx-target "#detail"} "Browse / author hull"]
         [:form (form-attrs "/assembly/reset")
          (hidden "revision" revision) [:button {:type "submit"} "Reset draft"]]]
        (when (empty? (:slots derived)) [:p "This hull has no usable sockets. Pick and save its socket faces to add slots."])
        (for [{:keys [code slot part-id]} (:errors derived)]
          [:p.detail__error {:role "status"}
           (str (or (get responses/messages code)
                    (case code :incomplete-split "Pick this socket's face again to define its capacity split."
                          :invalid-socket "Reauthor the malformed socket."
                          (name code)))
                " " part-id (when (seq slot) (str " — " (slot-label slot))))])
        [:div.assembly__slots
         (map #(slot-view database root revision available %) (:slots derived))]])
     (for [[id {:keys [state message]}] (sort-by key prepared) :when (not= :ready state)]
       [:div.assembly__preparation
        (if (= :failed state)
          [:p.detail__error "Could not prepare " id ": " message " "
           [:a {:href (str "/assembly?retry=" (urls/encode-id id))
                :hx-get (str "/assembly?retry=" (urls/encode-id id)) :hx-target "#detail"} "Retry"]]
          [:p {:role "status"} "Preparing " id "…"])])
     (when pending?
       [:div {:hx-get "/assembly?poll=1" :hx-trigger "load delay:400ms"
              :hx-target "#detail" :hx-swap "innerHTML" :hx-sync "#detail:abort"}])]))
