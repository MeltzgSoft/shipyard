(ns shipyard.bulk-orientation.views
  "Server-rendered selection table, preview grid, and save result."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.http.views :as http-views]
            [shipyard.http.urls :as urls]
            [shipyard.file-picker.views :as file-picker]
            [shipyard.http.pagination :as pagination]
            [shipyard.mount.wizard :as wizard]
            [shipyard.part.orientation :as orientation]
            [shipyard.workspace.views :as workspace-views]
            [shipyard.workspace.transforms :as workspace-transforms]))

(defn- angle-label [degrees]
  (str (/ (Math/round (* 10.0 degrees)) 10.0) "°"))

(defn- mount-label [{:keys [plugs sockets]}]
  (let [labels (concat (when (pos? (or plugs 0)) [(if (= 1 plugs) "Plug" (str plugs " plugs"))])
                       (sort (for [[accepts capacity] sockets]
                               (str (if (seq accepts) (str/join "/" (sort (map name accepts))) "any") " ×" capacity))))]
    (if (seq labels) (str/join " · " labels) "None")))

(defn- import-files [part]
  (let [files (:import/files part)]
    [:details.import-files
     [:summary (str (count files) (if (= 1 (count files)) " file" " files"))]
     (for [{:keys [key chain variant]} files]
       [:label.import-files__file
        [:span {:title (str/join " → " chain)} (str/join " → " chain)]
        [:select {:aria-label (str "Variant for " (last chain)) :data-import-file key
                  :hx-post "/imports/variant" :hx-trigger "change"
                  :hx-vals (str "js:{file:" (json/write-str key) ",variant:this.value}")
                  :hx-params "file,variant" :hx-target "#bulk-orient-selection" :hx-swap "outerHTML"
                  :hx-sync "#workspace-navigation:drop"
                  :hx-disabled-elt "#library button, #library select, [data-bulk-select]"}
         (for [[value label] [[:unsupported "Unsupported"] [:supported "Supported"] [:unsupported-pitted "Unsupported (pitted)"]]]
           [:option {:value (name value) :selected (= variant value)} label])]])
     (when (> (count files) 1)
       [:button {:type "button" :data-import-split (:part/id part)
                 :hx-post "/imports/split" :hx-vals (json/write-str {"group" (:part/id part)})
                 :hx-params "group" :hx-target "#bulk-orient-selection" :hx-swap "outerHTML"
                 :hx-sync "#workspace-navigation:drop"
                 :hx-disabled-elt "#library button, #library select, [data-bulk-select]"}
        "Split into separate rows"])]))

(defn- orientation-row [selected part]
  (let [[yaw pitch roll] (orientation/to-euler-degrees (:part/orientation part))
        reason (http-views/unrenderable-reason part)
        renderable? (nil? reason)]
    [:div.bulk-orient__row
     {:tabindex "0" :data-workspace-transition "true" :data-part-row (:part/id part)
      :hx-on:dblclick (when-not (:import/source part) "if(!this.hasAttribute('disabled')&&event.target.tagName!=='INPUT'){document.getElementById('part-open-id').value=this.dataset.partRow; document.getElementById('part-open').requestSubmit();}")
      :hx-on:keydown (when-not (:import/source part) "if(!this.hasAttribute('disabled')&&event.key==='Enter'){event.preventDefault(); document.getElementById('part-open-id').value=this.dataset.partRow; document.getElementById('part-open').requestSubmit();}")}
     [:input {:type "checkbox" :value (:part/id part) :aria-label (str "Select " (:part/name part))
              :data-bulk-select "true" :name "selected" :checked (contains? selected (:part/id part))}]
     [:span.part-thumbnail
      (if renderable?
        {:hx-get (str "/thumbnails/" (urls/encode-id (:part/id part)))
         :hx-trigger "intersect once root:#bulk-orient-results" :hx-sync "this:drop" :hx-disabled-elt "this" :hx-target "this" :hx-swap "innerHTML"}
        {:title reason})
      (if renderable? "…" "No preview")]
     [:span.bulk-orient__part (:part/name part)]
     [:span.bulk-orient__bundle (:part/bundle part)]
     [:span.bulk-orient__role (name (or (:part/role-hint part) :unknown))]
     [:span.bulk-orient__class (or (:part/class part) "—")]
     [:span.bulk-orient__mounts (if (:import/source part)
                                  (if (:import/conflict? part) [:strong.detail__error "Assign variants"]
                                      (str/join " + " (sort (map name (:part/variants part)))))
                                  (mount-label (:part/mount-summary part)))]
     [:span.bulk-orient__regions {:title (:import/source part) :data-has-regions (str (boolean (:part/has-regions? part)))}
      (if (:import/source part) (import-files part) (if (:part/has-regions? part) "Yes" "No"))]
     [:code (if (bulk/saved? part) (angle-label yaw) "—")]
     [:code (if (bulk/saved? part) (angle-label pitch) "—")]
     [:code (if (bulk/saved? part) (angle-label roll) "—")]
     [:span {:class (if (bulk/saved? part) "bulk-orient__saved" "bulk-orient__unset")}
      (cond
        (not renderable?) "No preview"
        (bulk/saved? part) "Saved"
        :else "Unset")]]))

(defn results
  ([parts] (results parts #{}))
  ([parts selected] (results parts selected "0"))
  ([parts selected scroll] (results parts selected scroll nil))
  ([parts selected scroll page]
   (let [window (pagination/window parts page) parts (:items window)]
     [:div#bulk-orient-results.bulk-orient__results
      {:data-scroll-top (or scroll "0") :hx-on--load "if(event.target===this){this.scrollTop=Number(this.dataset.scrollTop)}"
       :onscroll "document.getElementById('part-table-position').value=this.scrollTop"}
      [:input#part-table-position {:type "hidden" :name "table-scroll" :value (or scroll "0")}]
      [:input {:type "hidden" :name "page" :value (:page window) :data-part-page true}]
      [:p.results__count (format "%d matches" (:total window))]
      (pagination/controls window "/orient/parts" "#bulk-orient-results" "#bulk-orient-filters")
      [:form.bulk-orient__table {:role "group" :aria-label "Parts"
                                 :method "post" :action "/orient/selection" :hx-post "/orient/selection" :hx-trigger "change[target.matches('[data-bulk-select]')]" :hx-include "#part-table-position, [data-part-page]" :hx-target "#bulk-orient-selection"
                                 :hx-swap "outerHTML" :hx-sync "this:replace"
                                 :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition], .part-bulk-edit button, [data-import-group]"}
       [:input {:type "hidden" :name "visible" :value (pr-str (mapv :part/id parts))}]
       [:div.bulk-orient__columns {:aria-hidden "true"}
        [:span] [:span "Preview"] [:span "Part"] [:span "Bundle / faction"] [:span "Role"] [:span "Class"] [:span (if (:import/source (first parts)) "Variant" "Mount summary")] [:span (if (:import/source (first parts)) "Files / variants" "Regions")] [:span "Yaw"] [:span "Pitch"]
        [:span "Roll"] [:span "Orientation"]]
       (if (seq parts)
         (map (partial orientation-row selected) parts)
         [:p.bulk-orient__empty "No parts match these filters."])]])))

(defn- selection-controls [selection]
  (let [ids (bulk/selected-ids selection)]
    [:form#bulk-orient-selection.bulk-orient__selection
     (merge workspace-views/transition-attrs
            {:method "post" :action "/orient/render" :hx-post "/orient/render" :hx-target "#detail"
             :data-bulk-render "true" :hx-include "#bulk-orient-filters, #part-table-position, [data-part-page]"})
     [:input {:type "hidden" :name "part-ids" :value (or selection "[]") :data-bulk-ids "true"}]
     [:p [:strong {:data-bulk-count "true"} (str (count ids) " selected")]]
     [:button {:type "submit" :disabled (empty? ids) :data-bulk-render-button "true" :data-workspace-transition "true"} "Orient selection →"]]))

(defn- apply-button [selection]
  [:button#part-bulk-apply {:type "submit" :disabled (empty? (bulk/selected-ids selection))} "Apply to selected"])

(defn selection-updates [selection]
  (list (selection-controls selection)
        (update (apply-button selection) 1 assoc :hx-swap-oob "outerHTML")
        [:p#part-edit-status {:role "status" :hx-swap-oob "outerHTML"}]))

(defn selection-form
  ([selection] (selection-form selection false))
  ([selection importing?]
   [:div#bulk-selection
    (selection-controls selection)
    [:form.part-bulk-edit {:method "post" :action "/parts/metadata" :hx-post "/parts/metadata" :hx-target "#part-edit-status"
                           :hx-include "#part-table-position, [data-part-page]" :hx-disabled-elt (if importing? "find button, .import-review button" "find button")}
     [:label "Field" [:select {:name "field"
                               :hx-on:change "var input=this.form.querySelector('input[name=value]'); if(this.value==='role'){input.setAttribute('list','part-role-values');}else{input.removeAttribute('list');}"}
                      (for [[value label] (cond-> [["bundle" "Bundle / faction"] ["class" "Class"] ["role" "Role"] ["name" "Name"]] importing? (conj ["variant" "Supported / unsupported"]))]
                        [:option {:value value} label])]]
     [:label.part-bulk-edit__name "Name operation" [:select {:name "operation"}
                                                    [:option {:value "replace"} "Find and replace"]
                                                    [:option {:value "prefix"} "Add prefix"]
                                                    [:option {:value "suffix"} "Add suffix"]
                                                    [:option {:value "set"} "Replace entire name"]]]
     [:label.part-bulk-edit__find "Find" [:input {:name "find"}]]
     [:label "Value" [:input {:name "value"}]]
     [:datalist#part-role-values (for [role wizard/role-options] [:option {:value (name role)}])]
     (apply-button selection)]
    [:p#part-edit-status {:role "status"}]]))

(defn panel
  ([facets] (panel facets nil))
  ([facets selection] (panel facets selection nil))
  ([facets selection root] (panel facets selection root nil))
  ([facets selection root import-session]
   [:section#library.panel.bulk-orient
    (when-not import-session (http-views/settings-panel root))
    [:header.bulk-orient__head [:h2 (if import-session "Part Browser · Import mode" "Part Browser")]
     [:p (if import-session "Review inferred fields. Select rows for bulk edits and orientation. Mount authoring and region painting are disabled."
             "Select rows to edit fields or orient together. Double-click a part to open its editor.")]]
    (if import-session
      [:div.import-review
       [:p "Archive: " (:archive import-session)]
       (when-let [skipped (seq (:skipped-empty-archives import-session))]
         [:details.import-warnings
          [:summary (str "Skipped " (count skipped) " empty nested ZIP " (if (= 1 (count skipped)) "file" "files"))]
          [:ul (for [chain skipped] [:li (str/join " → " chain)])]])
       (for [[selection label] [["all" "Select entire import"] ["none" "Clear selection"]]]
         [:form {:method "post" :action "/imports/selection" :hx-post "/imports/selection" :hx-target "#bulk-orient-selection" :hx-swap "outerHTML"
                 :hx-sync "#workspace-navigation:drop"
                 :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition], [data-bulk-select], [data-import-select], .part-bulk-edit button, [data-import-group]"}
          [:input {:type "hidden" :name "selection" :value selection}]
          [:button {:type "submit" :data-import-select selection} label]])
       [:form {:method "post" :action "/imports/group" :hx-post "/imports/group"
               :hx-target "#bulk-orient-selection" :hx-swap "outerHTML"
               :hx-sync "#workspace-navigation:drop"
               :hx-disabled-elt "#library button, #library select, [data-bulk-select]"}
        [:input {:name "name" :placeholder "Optional group name" :aria-label "Grouped part name"}]
        [:button {:type "submit" :data-import-group true} "Group selected rows"]]
       [:p "Matching versions share a row. Expand Files / variants to assign each file or split a group. Select rows to group missed matches; the resulting row shares its labels and orientation."]
       [:p "Original ZIP archives are kept. Only unambiguous unsupported models have previews."]
       [:form (merge workspace-views/transition-attrs {:method "post" :action "/imports/commit" :hx-post "/imports/commit" :hx-target "#detail" :hx-disabled-elt "find button"})
        [:button {:type "submit" :data-workspace-transition true} "Import into library"]]
       [:form (merge workspace-views/transition-attrs {:method "post" :action "/imports/cancel" :hx-post "/imports/cancel" :hx-target "#detail"})
        [:button {:type "submit" :data-workspace-transition true} "Cancel import"]]
       [:p#import-status {:role "status"}]]
      [:form.import-start (merge workspace-views/transition-attrs {:method "post" :action "/imports/start" :hx-post "/imports/start" :hx-target "#detail" :hx-disabled-elt "find button"})
       (file-picker/field {:id "import-archive"})
       [:button {:type "submit" :disabled (nil? root)} "Review archive"]
       [:span.htmx-indicator "Unpacking archive…"]
       [:p#import-status {:role "status"}]])
    [:form#bulk-orient-filters.filters
     {:data-workspace-filters "true" :hx-get "/orient/parts" :hx-target "#bulk-orient-results" :hx-swap "outerHTML"
      :hx-vals "js:{page: event.type==='load' ? (document.querySelector('[data-part-page]')?.value || '1') : '1', 'table-scroll': event.type==='load' ? (document.querySelector('#part-table-position')?.value || '0') : '0'}"
      :hx-trigger "load, change[target.tagName === 'SELECT'], search, keyup changed delay:300ms"}
     [:label.filters__field "Bundle" [:select {:name "bundle"} (http-views/options "All bundles" (:bundles facets))]]
     [:label.filters__field "Class" [:select {:name "class"} (http-views/options "All classes" (:classes facets))]]
     [:label.filters__field "Role" [:select {:name "role"} (http-views/options "All roles" (map name (:roles facets)))]]
     [:label.filters__field "Orientation" [:select {:name "orientation"}
                                           [:option {:value "all"} "Any orientation"]
                                           [:option {:value "unset"} "Orientation unset"]
                                           [:option {:value "saved"} "Orientation saved"]]]
     [:label.filters__field "Name" [:input {:type "search" :name "q" :placeholder "Search names"}]]]
    [:form#part-open (merge workspace-views/transition-attrs
                            {:hidden true :method "get" :action "/workspace/browse" :hx-get "/workspace/browse" :hx-target "#detail" :hx-swap "innerHTML settle:0ms"
                             :hx-include "#bulk-orient-filters, #part-table-position, [data-part-page]"})
     [:input#part-open-id {:type "hidden" :name "part-id"}]]
    [:div#bulk-orient-results.bulk-orient__results
     [:input {:type "hidden" :name "page" :value "1" :data-part-page true}]
     [:input {:id "part-table-position" :type "hidden" :name "table-scroll" :value "0"}]
     [:p.muted "Loading parts…"]]
    (selection-form selection (some? import-session))]))

(defn filter-updates [facets filters]
  (for [[field key label] [["bundle" :bundles "All bundles"] ["class" :classes "All classes"] ["role" :roles "All roles"]]
        :let [values (->> (conj (mapv name (get facets key)) (get filters field))
                          (remove #(or (nil? %) (= "" %))) (distinct) (sort))]]
    (workspace-transforms/selected-filters
     [:select {:name field :hx-swap-oob (str "outerHTML:#bulk-orient-filters select[name=" field "]")}
      (http-views/options label values)] filters)))

(defn grid [entries]
  (let [ids (mapv :part/id entries)
        preparing? (some #(= :preparing (:state %)) entries)]
    [:section.bulk-grid {:data-bulk-grid "true"}
     [:header.bulk-grid__toolbar
      [:button (merge workspace-views/transition-attrs {:type "button" :data-bulk-back "true" :data-workspace-transition "true"
                                                        :hx-get "/workspace/browse?table=1" :hx-target "#detail" :hx-swap "innerHTML settle:0ms"}) "← Back to table"]
      [:span#bulk-grid-controls.bulk-grid__controls
       [:strong [:span {:data-bulk-grid-count "true"} (str (count entries) " selected")]]
       [:span.bulk-grid__divider]
       (for [[axis label] [["x" "Pitch"] ["y" "Yaw"] ["z" "Roll"]]]
         [:span.bulk-grid__control [:span {:class (str "bulk-grid__axis bulk-grid__axis--" axis)} label]
          [:button {:type "button" :disabled preparing? :data-bulk-rotate "true" :data-axis axis :data-direction "-1"} "−"]
          [:input {:type "number" :disabled preparing? :step "1" :inputmode "decimal"
                   :placeholder "degrees" :aria-label (str "Set " label " degrees for selection")
                   :data-bulk-angle "true" :data-axis axis}]
          [:button {:type "button" :disabled preparing? :data-bulk-rotate "true" :data-axis axis :data-direction "1"} "+"]])]
      ;; Step choices remain interactive while preparing; polling must not detach
      ;; a pressed button before pointer-up can complete its click.
      [:span.bulk-grid__steps {:aria-label "Rotation step"}
       (for [step [1 15 90]]
         [:button {:type "button" :data-bulk-step step :aria-pressed (str (= 90 step))} (str step "°")])]
      [:span#bulk-grid-actions.bulk-grid__controls
       [:button {:type "button" :disabled preparing? :data-bulk-copy "true"} "Copy first"]
       [:button {:type "button" :disabled preparing? :data-bulk-reset "true"} "Reset"]]]
     [:div.bulk-grid__cards
      (when preparing?
        [:span.bulk-grid__poll
         {:hidden true :hx-post "/orient/render" :hx-trigger "load delay:400ms"
          :hx-target ".bulk-grid__cards" :hx-swap "outerHTML"
          :hx-select ".bulk-grid__cards" :hx-select-oob "#bulk-grid-controls,#bulk-grid-actions"
          :hx-vals (json/write-str {"part-ids" (pr-str ids) "poll" "1"})}])
      (for [{:part/keys [id name orientation] :keys [mesh-url mesh-key state message]} entries]
        [:article.bulk-grid__card
         (cond-> {:data-bulk-part id :data-orientation (pr-str orientation)}
           mesh-url (assoc :data-mesh-url mesh-url :data-mesh-key mesh-key)
           (= state :preparing) (assoc :data-preparing "true"))
         [:div.bulk-grid__preview {:data-bulk-preview "true"}
          [:span.bulk-grid__card-state {:role "status"} (if mesh-url "Loading preview…" (or message "Preparing…"))]
          (when mesh-url [:button {:type "button" :hidden true :data-bulk-retry "true"} "Retry preview"])]
         [:p name]])]
     [:footer.bulk-grid__footer
      [:span#bulk-orient-status "Preview changes are not saved."]
      [:form {:method "post" :action "/orient/save" :hx-post "/orient/save" :hx-target "#bulk-orient-status" :hx-swap "innerHTML"
              :data-bulk-save "true"}
       [:input {:type "hidden" :name "request" :value "0"}]
       [:input {:type "hidden" :name "orientations" :value "{}" :data-bulk-orientations "true"}]
       [:button {:type "submit" :disabled true :data-bulk-save-button "true"} "Save orientations"]]]]))

(defn save-result [{:keys [saved failed request activation]}]
  [:span {:data-bulk-save-result (pr-str {:saved saved :request request :activation activation})}
   (if (seq failed)
     (str "Saved " (count saved) ". Failed: " (str/join ", " failed))
     (str "Saved " (count saved) " orientation" (when (not= 1 (count saved)) "s") "."))])
