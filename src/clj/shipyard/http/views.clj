(ns shipyard.http.views
  "Every panel, as hiccup (TECHNICAL.md §7).

  Pure functions of catalog data - no request map, no I/O - so the whole UI is
  unit-testable without opening a socket (§10.1). The handlers in
  `shipyard.http.routes` do the looking-up; this namespace only decides what a
  thing looks like."
  (:require [clojure.string :as str]
            [shipyard.http.urls :as urls]
            [shipyard.interface-colors :as interface-colors]
            [shipyard.mount.wizard :as wizard]))

;; --- parts ------------------------------------------------------------------

(defn unrenderable-reason
  "Why a part cannot be previewed, in the user's terms, or nil when it can.

  Never a reason to hide it. 73 folders in this library ship only
  `supported.stl`, and a browser that omits what you own is lying to you
  (§5.3)."
  [{:part/keys [renderable variants]}]
  (when-not renderable
    (if (contains? (set variants) :supported)
      (str "Ships only as a supported STL. The print supports are fused into "
           "that mesh, so a preview would show the scaffold rather than the part.")
      "No STL in this folder that Shipyard can open.")))

(defn- role-label [{:part/keys [role-hint role-source]}]
  [:span.part__role
   {:title (case role-source
             :class "Taken from the folder layout."
             :manual "Set in this part's sidecar."
             "Guessed from the part name - roughly one in ten is wrong.")
    :class (when (= :inferred role-source) "part__role--guessed")}
   (name (or role-hint :unknown))])

(defn part-card
  "One row in the library list. An unpreviewable part is greyed and carries its
  reason, but stays selectable: its metadata is still worth reading."
  [{:part/keys [id] :as part}]
  (let [reason (unrenderable-reason part)]
    [:li.part {:class (when reason "part--unrenderable")}
     [:button.part__select
      {:type      "button"
       :hx-get    (urls/part-url id)
       :hx-target "#detail"
       :hx-swap   "innerHTML"}
      [:span.part__name (:part/name part)]
      (role-label part)]
     [:p.part__path id]
     (when reason [:p.part__reason reason])]))

(defn library-results
  "The `GET /library` fragment.

  Carries no `hx-trigger` of its own on purpose: the shell's copy of this id has
  a `load` trigger, and repeating it here would make the panel refetch itself
  forever."
  [parts]
  [:div#library-results.results
   [:p.results__count
    (case (count parts)
      0 "No parts match."
      1 "1 part"
      (format "%,d parts" (count parts)))]
   (when (seq parts)
     [:ul.parts (map part-card parts)])])

;; --- part detail ------------------------------------------------------------

(defn- detail-head [{:part/keys [id bundle class] :as part}]
  [:header.detail__head
   [:h2.detail__name (:part/name part)]
   [:p.detail__crumbs (str/join " › " (remove nil? [bundle class]))]
   [:p.detail__id id]])

(defn- poll
  "Self-sustaining poll. `load` fires again every time this element is inserted,
  so the cycle continues while the server keeps sending it and stops the moment
  a ready or failed fragment arrives without it - no timer to cancel."
  [id]
  [:div.detail__poll
   {:hx-get     (urls/part-url id)
    :hx-trigger "load delay:400ms"
    :hx-target  "#detail"
    :hx-swap    "innerHTML"}])

(defn- mount-list [{:part/keys [id mounts]}]
  (when (seq mounts)
    (let [part-id id]
      [:section.mounts
       [:h3.mounts__title "Mounts"]
       [:ul.mounts__list
        (for [{:mount/keys [kind accepts capacity] :as mount} mounts]
          [:li.mounts__row
           [:span.mounts__summary
            [:code (name (:mount/id mount))] " " (name kind)
            (when (seq accepts)
              [:span.mounts__accepts " -> " (str/join ", " (map name accepts))])
            (when (and (= :socket kind) (> (long (or capacity 1)) 1))
              [:span.mounts__accepts " x" capacity])
            [:span.mounts__accepts " / " (name (or (:mount/origin mount) :picked))]]
           [:div.mounts__actions
            [:form.mounts__action
             {:hx-post   "/mounts/edit"
              :hx-target "#detail"
              :hx-swap   "innerHTML"}
             [:input {:type "hidden" :name "part-id" :value part-id}]
             [:input {:type "hidden" :name "mount-id" :value (name (:mount/id mount))}]
             [:button {:type "submit"} "Edit"]]
            [:form.mounts__action
             {:hx-post   "/mounts/delete"
              :hx-target "#detail"
              :hx-swap   "innerHTML"}
             [:input {:type "hidden" :name "part-id" :value part-id}]
             [:input {:type "hidden" :name "mount-id" :value (name (:mount/id mount))}]
             [:button {:type "submit"} "Delete"]]]])]])))

(defn- interface-legend [{:part/keys [mounts]}]
  (when (seq mounts)
    [:section.interface-legend
     [:h3.interface-legend__title "Interface colors"]
     [:ul.interface-legend__list
      (for [{:keys [type label color]} (interface-colors/legend-items mounts)]
        [:li.interface-legend__item
         [:span.interface-legend__swatch
          {:style (str "--interface-color:" color)
           :aria-hidden "true"}]
         [:span {:data-interface-type (name type)} label]])]]))

(defn- durable-mounts [mounts]
  (mapv #(dissoc % :db/id) mounts))

(declare facet-preview facet-error)

(defn- dismiss-error-button [{:part/keys [id]}]
  [:button.detail__dismiss
   {:type      "button"
    :hx-get    (urls/part-url id)
    :hx-target "#detail"
    :hx-swap   "innerHTML"}
   "Dismiss"])

(defn- role-choice [selected role]
  [:option {:value (name role) :selected (= selected role)} (name role)])

(defn- part-metadata [{:part/keys [id role-hint role-source]}]
  [:section.part-metadata
   [:h3.part-metadata__title "Part metadata"]
   [:form.part-metadata__form
    {:hx-post   "/parts/role"
     :hx-target "#detail"
     :hx-swap   "innerHTML"}
    [:input {:type "hidden" :name "part-id" :value id}]
    [:label.part-metadata__field "Role"
     [:select {:name "part-role"}
      (map (partial role-choice (or role-hint :unknown)) wizard/role-options)]]
    [:button {:type "submit"} "Save role"]]
   [:p.part-metadata__source
    (case role-source
      :manual "Manual"
      :class "From folder"
      "Inferred")]])

(defn detail-preparing [{:part/keys [id] :as part}]
  [:div.detail
   (detail-head part)
   [:p.detail__status "Preparing this part for display. A large hull takes a few seconds; it is cached afterwards."]
   (poll id)])

(defn detail-ready
  ([part mesh-key] (detail-ready part mesh-key nil))
  ([part mesh-key {:keys [error preview repeat-values]}]
   [:div.detail.detail--ready
    [:div.detail__summary
     (detail-head part)
     [:p.detail__status "Loaded."]
     (part-metadata part)
     (interface-legend part)
     (mount-list part)]
    [:div#mount-authoring.mount-wizard
     (cond-> {:data-part-id (:part/id part)
              :data-mesh-key mesh-key
              :data-interface-mounts (pr-str (durable-mounts (:part/mounts part)))}
       repeat-values (assoc :data-repeat-values (pr-str repeat-values)))
     [:button.mount-wizard__toggle
      {:type                  "button"
       :data-authoring-toggle "true"
       :data-part-id          (:part/id part)
       :data-mesh-key         mesh-key
       :aria-pressed          "false"}
      "Pick mount face"]
     [:div#facet-preview
      (cond
        preview (facet-preview preview)
        error (facet-error error part))]]]))

(defn detail-failed [{:part/keys [id] :as part} message]
  [:div.detail
   (detail-head part)
   [:p.detail__error "Shipyard could not prepare this part: " [:code message]]
   [:button.detail__retry
    {:type      "button"
     :hx-get    (str (urls/part-url id) "?retry=1")
     :hx-target "#detail"
     :hx-swap   "innerHTML"}
    "Try again"]])

(defn detail-unrenderable [part reason]
  [:div.detail.detail--unrenderable
   (detail-head part)
   [:p.detail__reason reason]])

(defn detail-empty []
  [:div.detail.detail--empty
   [:p.muted "Select a part to view it."]])

(defn detail-missing [id]
  [:div.detail.detail--empty
   [:p.detail__error "No such part: " [:code id]]])

;; --- facet preview ----------------------------------------------------------

(defn- plane-choice [selected plane]
  [:option {:value (name plane) :selected (= selected plane)} (name plane)])

(defn- default-kind [part]
  (if (#{:hull :hull-section} (:part/role-hint part)) :socket :plug))

(defn- mount-form [{:keys [part frame mode original-mount-id values]}]
  (let [kind (or (:kind values) (default-kind part))
        accepts (or (:accepts values) #{:weapon})
        capacity (or (:capacity values) 1)
        mount-id (or (:mount-id values) "mount-1")
        mirror-id (some-> mount-id (keyword) (wizard/suggest-mirror-id) (name))
        edit? (= :edit mode)]
    [:form.mount-wizard__form
     {:hx-post   "/mounts"
      :hx-target "#detail"
      :hx-swap   "innerHTML"}
     [:input {:type "hidden" :name "part-id" :value (:part/id part)}]
     [:input {:type "hidden" :name "frame" :value (pr-str frame)}]
     (when edit?
       [:input {:type "hidden" :name "original-mount-id" :value (name original-mount-id)}])
     [:label.mount-wizard__field "Mount id"
      [:input {:type "text" :name "mount-id" :value mount-id
               :autocomplete "off" :spellcheck "false"}]]
     [:label.mount-wizard__field "Kind"
      [:select {:name "kind"}
       (for [k wizard/kind-options]
         [:option {:value (name k) :selected (= k kind)} (name k)])]]
     [:fieldset.mount-wizard__roles
      [:legend "Accepts"]
      (for [role wizard/role-options]
        [:label.mount-wizard__check
         [:input {:type "checkbox" :name "accepts" :value (name role)
                  :checked (contains? accepts role)}]
         (name role)])]
     [:label.mount-wizard__field "Capacity"
      [:input {:type "number" :name "capacity" :value capacity
               :min "1" :step "1"}]]
     [:label.mount-wizard__field "Roll"
      [:input {:type "number" :name "roll-deg" :value (or (:roll-deg values) "0")
               :step "1"}]]
     (when-not edit?
       [:fieldset.mount-wizard__mirror
        [:legend "Mirror"]
        [:label.mount-wizard__check
         [:input {:type "checkbox" :name "mirror" :value "true"}]
         "Mirror socket"]
        [:label.mount-wizard__field "Plane"
         [:select {:name "mirror-plane"}
          (map (partial plane-choice :x) wizard/symmetry-plane-options)]]
        [:label.mount-wizard__field "Offset"
         [:input {:type "number" :name "mirror-offset" :value "0" :step "0.01"}]]
        [:label.mount-wizard__field "Mirrored id"
         [:input {:type "text" :name "mirror-id" :value mirror-id
                  :autocomplete "off" :spellcheck "false"}]]])
     (when-not edit?
       [:label.mount-wizard__check
        [:input {:type "checkbox" :name "repeat" :value "true"}]
        "Repeat classification"])
     [:ul.mount-wizard__orientation
      [:li [:span.mount-wizard__swatch.mount-wizard__swatch--axis] "Normal (+Z)"]
      [:li [:span.mount-wizard__swatch.mount-wizard__swatch--roll] "Roll (+X)"]
      [:li [:span.mount-wizard__swatch.mount-wizard__swatch--up] "Up (+Y)"]]
     [:div.mount-wizard__actions
      (if edit?
        [:button {:type "submit" :name "action" :value "update"} "Save changes"]
        (for [[action label] [[:create "Save mount"] [:replace "Replace"]]]
          [:button {:type "submit" :name "action" :value (name action)} label]))
      [:button.detail__dismiss
       {:type      "button"
        :hx-get    (urls/part-url (:part/id part))
        :hx-target "#detail"
        :hx-swap   "innerHTML"}
       "Cancel"]]]))

(defn facet-preview
  ([] [:div.facet-preview
       [:p.detail__status "Face selected."]])
  ([{:keys [frame part] :as preview}]
   [:div.facet-preview
    (when-let [error (:error preview)]
      [:div.facet-preview__message
       [:p.detail__error error]
       (when part
         (dismiss-error-button part))])
    (when frame
      [:p.detail__status "Face selected."])
    (when frame
      (mount-form preview))]))

(defn facet-error
  ([message] (facet-error message nil))
  ([message part]
   [:div.facet-preview.facet-preview--error
    [:div.facet-preview__message
     [:p.detail__error message]
     (when part
       (dismiss-error-button part))]]))

(defn mount-saved []
  [:div.facet-preview
   [:p.detail__status "Mount saved."]])

;; --- the library location ---------------------------------------------------

(defn settings-form
  "Where the library is, and how to change it (issue #35).

  `id` because this form appears twice on a first run - collapsed in the panel
  header, and open in the results area where the parts would have been - and
  two elements cannot share one. htmx targets the message box by id, so the two
  copies must not fight over it."
  [{:keys [id root error]}]
  [:form.settings__form
   {:id        id
    :hx-post   "/settings"
    :hx-target (str "#" id "-message")
    :hx-swap   "innerHTML"}
   [:label.settings__field {:for (str id "-root")} "Library folder"
    [:input {:type         "text"
             :id           (str id "-root")
             :name         "root"
             :value        (or root "")
             :placeholder  "/path/to/your/models"
             :autocomplete "off"
             :spellcheck   "false"}]]
   [:button.settings__save {:type "submit"} "Use this folder"]
   [:div.settings__message {:id (str id "-message")}
    (when error [:p.detail__error error])]])

(defn settings-panel
  "The always-available copy, collapsed. A library you have already found is
  not something you want a form about, but changing it must not require finding
  a config file."
  [root]
  [:details.settings
   [:summary.settings__summary "Library folder"]
   [:p.settings__current (if root [:code root] [:span.muted "not set"])]
   (settings-form {:id "settings" :root root})])

;; --- shell ------------------------------------------------------------------

(defn- options [selected-label values]
  (cons [:option {:value ""} selected-label]
        (for [v values] [:option {:value (str v)} (str v)])))

(defn filter-form
  "The filter controls live in the shell rather than in the `/library`
  fragment. The library does not change while the process runs, so there is
  nothing to re-render - and leaving the search box untouched is what keeps the
  caret in it while you type."
  [{:keys [bundles classes roles]}]
  [:form#filters.filters
   {:hx-get     "/library"
    :hx-target  "#library-results"
    :hx-swap    "outerHTML"
    ;; `load from:body` is what populates the list on first paint.
    :hx-trigger "load from:body, change, search, keyup changed delay:300ms"}
   [:label.filters__field "Bundle"
    [:select {:name "bundle"} (options "All bundles" bundles)]]
   [:label.filters__field "Class"
    [:select {:name "class"} (options "All classes" classes)]]
   [:label.filters__field "Role"
    [:select {:name "role"} (options "All roles" (map name roles))]]
   [:label.filters__field "Name"
    [:input {:type "search" :name "q" :placeholder "Search names" :autocomplete "off"}]]])

(defn shell
  "`GET /`. The canvas is created once here and never again: it is an island
  holding a WebGL context and hundreds of megabytes of GPU buffers, so it is
  marked `hx-preserve` and is never the target of a swap (SPEC §6.1)."
  [facets root]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Shipyard"]
    [:link {:rel "stylesheet" :href "/app.css"}]
    ;; htmx is a separate file from the viewport bundle so a broken viewport
    ;; build cannot take the whole UI down with it (TECHNICAL.md §8).
    [:script {:src "/js/htmx.min.js" :defer true}]
    [:script {:src "/js/viewport.js" :defer true}]]
   [:body
    [:header.masthead
     [:h1 "Shipyard"]
     [:p.masthead__tagline "Preview and assemble Battlefleet Gothic miniatures."]]
    [:main.layout
     [:section#library.panel
      [:h2.panel__title "Library"]
      (settings-panel root)
      (filter-form facets)
      [:div#library-results.results
       [:p.muted "Loading the library…"]]]
     [:section.stage
      [:canvas#viewport.stage__canvas {:hx-preserve "true"}]
      [:aside#detail.panel.stage__detail (detail-empty)]]]]])

(defn library-needs-root
  "First run: no root has ever been set. Ask for one where the parts would have
  been, rather than reporting an empty library - which would be true and
  useless."
  []
  [:div#library-results.results
   [:p "Shipyard does not know where your models are yet."]
   (settings-form {:id "setup" :root nil})])

(defn library-unavailable
  "A root was set, and it is not there any more - a renamed folder, or an
  unmounted drive. The process still starts, so say so where the parts would
  have been and offer the same form."
  [root]
  [:div#library-results.results
   [:p.detail__error "No library at " [:code root] "."]
   [:p.muted "The folder may have moved, or the drive it is on may not be mounted."]
   (settings-form {:id "setup" :root root})])
