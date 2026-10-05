(ns shipyard.bulk-orientation.views
  "Server-rendered selection table, preview grid, and save result."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.http.views :as http-views]
            [shipyard.http.urls :as urls]
            [shipyard.http.pagination :as pagination]
            [shipyard.vocabulary.views :as vocabulary]
            [shipyard.part-browser.views :as metadata]
            [shipyard.part.orientation :as orientation]
            [shipyard.part-browser.transforms :as parts]
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
  (let [files (or (:import/files part) (:library/files part))
        prefix (if (:import/source part) "/imports" "/parts/variants")]
    [:details.import-files
     [:summary (str (count files) (if (= 1 (count files)) " file" " files"))]
     (for [{:keys [key chain variant]} files]
       [:label.import-files__file
        [:span.part-thumbnail.import-file-thumbnail
         {:hx-get (str prefix "/thumbnails/" key)
          :hx-trigger "intersect once root:#bulk-orient-results" :hx-sync "this:drop"
          :hx-disabled-elt "this" :hx-target "this" :hx-swap "innerHTML"} "…"]
        [:span {:title (str/join " → " chain)} (str/join " → " chain)]
        [:select {:aria-label (str "Variant for " (last chain)) :data-import-file key :data-library-file (when-not (:import/source part) key)
                  :hx-post (str prefix "/variant") :hx-trigger "change"
                  :hx-vals (str "js:{file:" (json/write-str key) ",variant:this.value}")
                  :hx-params "file,variant" :hx-target "#bulk-orient-selection" :hx-swap "outerHTML"
                  :hx-sync "#workspace-navigation:drop"
                  :hx-disabled-elt "#library button, #library select, [data-bulk-select], [data-workspace-filters] input:enabled"}
         (for [[value label] [[:unsupported "Unsupported"] [:supported "Supported"] [:unsupported-pitted "Unsupported (pitted / recessed)"]]]
           [:option {:value (name value) :selected (= variant value)} label])]])
     (when (> (count files) 1)
       [:button {:type "button" :data-import-split (:part/id part)
                 :hx-post (str prefix "/split") :hx-vals (json/write-str {"group" (:part/id part)})
                 :hx-params "group" :hx-target "#bulk-orient-selection" :hx-swap "outerHTML"
                 :hx-sync "#workspace-navigation:drop"
                 :hx-disabled-elt "#library button, #library select, [data-bulk-select], [data-workspace-filters] input:enabled"}
        "Split into separate rows"])]))

(defn- row-orientation [part]
  (let [editable? (boolean (:part/renderable part))
        angles (orientation/to-euler-degrees (:part/orientation part))]
    [:fieldset.part-row-edit__orientation {:disabled (not editable?)}
     [:legend "Orientation"]
     [:input {:type "hidden" :name "orientation-action" :value "keep"}]
     [:div.part-row-edit__angles
      (for [[label field value] (map vector ["Yaw (Y) °" "Pitch (X) °" "Roll (Z) °"] parts/angle-fields angles)]
        [:label label
         [:input {:type "number" :name field :value (http-views/display-angle value) :step "any" :data-row-angle true
                  :hx-on:input "this.form.elements['orientation-action'].value='save'"}]])]
     [:button {:type "button" :data-row-orientation-reset true
               :hx-on:click "this.form.querySelectorAll('[data-row-angle]').forEach(i=>i.value='0');this.form.elements['orientation-action'].value='save'"}
      "Reset orientation"]
     [:p.muted (if editable? "Angles are saved with Save part. Reset restores the source orientation."
                   "Orientation needs an unambiguous unsupported source. Assign variants or restore the source first.")]]))

(defn row-editor [part message]
  (let [id (:part/id part) prefix (str "part-row-" (urls/encode-id id))]
    [:div.part-drawer__body
     [:span.part-thumbnail.part-thumbnail--large
      (if (parts/thumbnail? part (:import/source part))
        {:hx-get (str "/thumbnails/" (urls/encode-id id) "?size=large")
         :hx-trigger "intersect once root:#bulk-orient-results" :hx-sync "this:drop" :hx-target "this" :hx-swap "innerHTML"}
        {})
      (if (parts/thumbnail? part (:import/source part)) "…" "No preview")]
     [:form.part-row-edit {:method "post" :action "/parts/metadata/row" :hx-post "/parts/metadata/row"
                           :hx-params "*" :hx-include "unset" :hx-target "closest .part-drawer" :hx-swap "outerHTML" :hx-sync "#workspace-navigation:drop"
                           :hx-disabled-elt "find fieldset, [data-bulk-select], [data-select-all], [data-workspace-mode], [data-workspace-transition], .part-bulk-edit button"}
      [:input {:type "hidden" :name "part-id" :value id}]
      [:fieldset.part-row-edit__fields
       (metadata/metadata-fields prefix part)
       (row-orientation part)
       [:div.part-row-edit__actions [:button {:type "submit"} "Save part"]
        [:span {:role "status"} message]]]]
     (when (or (:import/source part) (seq (:library/files part))) (import-files part))]))

(defn orientation-row
  ([selected part] (orientation-row selected part false nil))
  ([selected part open? message]
   (let [[yaw pitch roll] (orientation/to-euler-degrees (:part/orientation part))
         reason (http-views/unrenderable-reason part)
         renderable? (nil? reason)
         thumbnail? (parts/thumbnail? part (:import/source part))]
     [:details.bulk-orient__row.part-drawer
      {:open open? :tabindex "0" :data-workspace-transition "true" :data-part-row (:part/id part)
       :hx-on:dblclick (when-not (:import/source part) "if(!this.hasAttribute('disabled')&&event.target.closest('summary')&&!event.target.closest('input,button,select,a')){document.getElementById('part-open-id').value=this.dataset.partRow; document.getElementById('part-open').requestSubmit();}")
       :hx-on:keydown (when-not (:import/source part) "if(!this.hasAttribute('disabled')&&event.key==='Enter'&&event.target===this){event.preventDefault(); document.getElementById('part-open-id').value=this.dataset.partRow; document.getElementById('part-open').requestSubmit();}")}
      [:summary.part-drawer__summary
       [:input {:type "checkbox" :value (:part/id part) :aria-label (str "Select " (:part/name part))
                :data-bulk-select "true" :name "selected" :checked (contains? selected (:part/id part))}]
       [:span.part-thumbnail
        (if thumbnail?
          {:hx-get (str "/thumbnails/" (urls/encode-id (:part/id part)))
           :hx-trigger "intersect once root:#bulk-orient-results" :hx-sync "this:drop" :hx-disabled-elt "this" :hx-target "this" :hx-swap "innerHTML"}
          {:title reason})
        (if thumbnail? "…" "No preview")]
       [:span.bulk-orient__part (:part/name part)]
       [:span.bulk-orient__bundle (:part/bundle part)]
       [:span.bulk-orient__role (name (or (:part/role-hint part) :unknown))]
       [:span.bulk-orient__class (or (:part/class part) "—")]
       (when-not (:import/source part)
         (for [[_ variant label] parts/variant-filters]
           [:span.bulk-orient__variant
            {:data-variant (name variant) :data-available (str (contains? (set (:part/variants part)) variant))
             :title (str (if (= variant :unsupported-pitted) "Unsupported (pitted / recessed)" label) " file")}
            (if (contains? (set (:part/variants part)) variant) "Yes" "No")]))
       [:span.bulk-orient__mounts (if (:import/source part)
                                    (if (:import/conflict? part) [:strong.detail__error "Assign variants"]
                                        (str/join " + " (sort (map name (:part/variants part)))))
                                    (mount-label (:part/mount-summary part)))]
       [:span.bulk-orient__regions {:title (:import/source part) :data-has-regions (str (boolean (:part/has-regions? part)))}
        (if (:import/source part) (str (count (:import/files part)) " files") (if (:part/has-regions? part) "Yes" "No"))]
       [:code (if (bulk/saved? part) (angle-label yaw) "—")]
       [:code (if (bulk/saved? part) (angle-label pitch) "—")]
       [:code (if (bulk/saved? part) (angle-label roll) "—")]
       [:span {:class (if (bulk/saved? part) "bulk-orient__saved" "bulk-orient__unset")}
        (cond
          (not renderable?) (if thumbnail? "Unavailable" "No preview")
          (bulk/saved? part) "Saved"
          :else "Unset")]]
      (if open?
        (row-editor part message)
        [:div.part-drawer__content
         {:hx-get (str "/parts/metadata/row?part-id=" (urls/encode-id (:part/id part)))
          :hx-trigger "toggle[event.target.open] once from:closest details" :hx-target "this" :hx-swap "innerHTML" :hx-sync "this:drop"}
         [:p "Loading part…"]])])))

(def selection-attrs
  {:hx-post "/orient/select-all" :hx-target "#bulk-orient-selection" :hx-swap "outerHTML"
   :hx-include "#bulk-orient-filters, #part-table-position, [data-part-page]"
   :hx-params (str/join "," (into ["selection"] (into pagination/filter-keys ["table-scroll" "page"])))
   :hx-sync "#workspace-navigation:drop"
   :hx-disabled-elt "[data-bulk-select], [data-select-all], [data-workspace-mode], [data-workspace-transition], .part-bulk-edit button"})

(defn matching-checkbox [parts selected]
  (let [ids (map :part/id parts)
        n (count (filter selected ids))
        all? (and (seq ids) (= n (count ids)))
        mixed? (and (pos? n) (not all?))]
    [:input#part-select-matching
     (merge selection-attrs
            {:type "checkbox" :aria-label "Select all matching parts" :title "Select or deselect all matching parts"
             :data-select-all "all" :checked (boolean all?) :disabled (empty? ids)
             :aria-checked (if mixed? "mixed" (str (boolean all?))) :data-indeterminate (str mixed?)
             :hx-trigger "change" :hx-vals "js:{selection:this.checked?'all':'matching-none'}"})]))

(defn results
  ([parts] (results parts #{}))
  ([parts selected] (results parts selected "0"))
  ([parts selected scroll] (results parts selected scroll nil))
  ([parts selected scroll page] (results parts selected scroll page false))
  ([parts selected scroll page chunk?]
   (results parts selected scroll page chunk? (boolean (:import/source (first parts)))))
  ([parts selected scroll page chunk? importing?]
   (let [checkbox (matching-checkbox parts selected)
         window (pagination/batch-window parts page chunk?) parts (:items window)
         rows (for [[n batch] (map-indexed vector (partition-all pagination/page-size parts))]
                [:div.list-chunk {:data-list-page (if chunk? (:page window) (inc n))}
                 (map (partial orientation-row selected) batch)])
         more (pagination/more window "/orient/parts" "#bulk-orient-results" "#bulk-orient-filters")]
     (if chunk?
       (list rows more)
       [:div#bulk-orient-results.bulk-orient__results
        {:data-scroll-top (or scroll "0") :hx-on--load "if(event.target===this){this.scrollTop=Number(this.dataset.scrollTop)}"
         :onscroll "document.getElementById('part-table-position').value=this.scrollTop"}
        [:input#part-table-position {:type "hidden" :name "table-scroll" :value (or scroll "0")}]
        [:input {:type "hidden" :name "page" :value (:page window) :data-part-page true}]
        [:p.results__count (format "%d matches" (:total window))]
        [:div.bulk-orient__table {:role "group" :aria-label "Parts"
                                  :data-library-variants (when-not importing? true)
                                  :method "post" :action "/orient/selection" :hx-post "/orient/selection" :hx-trigger "change[target.matches('[data-bulk-select]')]" :hx-include ".bulk-orient__table [data-bulk-select], #part-table-position, [data-part-page]" :hx-params "selected,visible,table-scroll,page" :hx-target "#bulk-orient-selection"
                                  :hx-swap "outerHTML" :hx-sync "this:replace"
                                  :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition], .part-bulk-edit button, [data-select-all], [data-import-group], [data-variant-group]"}
         [:div.bulk-orient__columns
          checkbox [:span "Preview"] [:span "Part"] [:span "Bundle / faction"] [:span "Role"] [:span "Class"]
          (when-not importing?
            (for [[_ variant label] parts/variant-filters]
              [:span {:title (when (= variant :unsupported-pitted) "Unsupported (pitted / recessed)")} label]))
          [:span (if importing? "Variant" "Mount summary")] [:span (if importing? "Files / variants" "Regions")] [:span "Yaw"] [:span "Pitch"]
          [:span "Roll"] [:span "Orientation"]]
         (if (seq parts)
           rows
           [:p.bulk-orient__empty "No parts match these filters."])
         more]]))))

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
     [:label "Field" [:select {:name "field"}
                      (for [[value label] (cond-> [["bundle" "Bundle / faction"] ["class" "Class"] ["role" "Role"] ["name" "Name"]] importing? (conj ["variant" "Supported / unsupported"]))]
                        [:option {:value value} label])]]
     [:label.part-bulk-edit__name "Name operation" [:select {:name "operation"}
                                                    [:option {:value "replace"} "Find and replace"]
                                                    [:option {:value "prefix"} "Add prefix"]
                                                    [:option {:value "suffix"} "Add suffix"]
                                                    [:option {:value "set"} "Replace entire name"]]]
     [:label.part-bulk-edit__find "Find" [:input {:name "find"}]]
     (vocabulary/picker)
     (apply-button selection)]
    [:p#part-edit-status {:role "status"}]]))

(defn- group-form [prefix]
  [:form {:method "post" :action (str prefix "/group") :hx-post (str prefix "/group")
          :hx-target "#bulk-orient-selection" :hx-swap "outerHTML" :hx-sync "#workspace-navigation:drop"
          :hx-disabled-elt "#library button, #library select, [data-bulk-select], [data-workspace-filters] input:enabled"}
   [:input {:name "name" :placeholder "Optional group name" :aria-label "Grouped part name"}]
   [:button {:type "submit" :data-variant-group true :data-import-group (when (= prefix "/imports") true)} "Group selected rows"]])

(defn panel
  ([facets] (panel facets nil))
  ([facets selection] (panel facets selection nil))
  ([facets selection root] (panel facets selection root nil))
  ([facets selection root import-session]
   [:section#library.panel.bulk-orient
    (pagination/progress)
    (if import-session
      [:div.import-review
       [:p "Archive: " (:archive import-session)]
       (when-let [skipped (seq (:skipped-empty-archives import-session))]
         [:details.import-warnings
          [:summary (str "Skipped " (count skipped) " empty nested ZIP " (if (= 1 (count skipped)) "file" "files"))]
          [:ul (for [chain skipped] [:li (str/join " → " chain)])]])
       (group-form "/imports")
       [:p "Matching versions share a row. Expand Files / variants to assign each file or split a group. Select rows to group missed matches; the resulting row shares its labels and orientation."]
       [:p "Original ZIP archives are kept. Thumbnails prefer unsupported files; supported-only rows show their print supports. Orientation editing requires an unambiguous unsupported file."]
       [:form (merge workspace-views/transition-attrs {:method "post" :action "/imports/commit" :hx-post "/imports/commit" :hx-target "#detail" :hx-disabled-elt "find button"})
        [:button {:type "submit" :data-workspace-transition true} "Import into library"]]
       [:form (merge workspace-views/transition-attrs {:method "post" :action "/imports/cancel" :hx-post "/imports/cancel" :hx-target "#detail"})
        [:button {:type "submit" :data-workspace-transition true} "Cancel import"]]
       [:p#import-status {:role "status"}]]
      [:div.library-variants
       [:form.import-start (merge workspace-views/transition-attrs {:method "post" :action "/imports/choose" :hx-post "/imports/choose" :hx-target "#detail"})
        [:button {:type "submit" :disabled (nil? root) :data-picker-browse true
                  :data-workspace-transition true :aria-label "Browse for ZIP archive"} "Import ZIP…"]
        [:span.htmx-indicator "Unpacking archive…"]
        [:p#import-status {:role "status"}]]
       (group-form "/parts/variants")
       [:p.muted "Select All variants to include supported-only rows. Group selected rows to fix missed matches; expand a row’s files to assign variants or split it. Library edits move files into canonical folders immediately."]
       [:p#variant-status {:role "status"}]])
    [:form#bulk-orient-filters.filters
     {:data-workspace-filters "true" :hx-get "/orient/parts" :hx-target "#bulk-orient-results" :hx-swap "outerHTML"
      :hx-sync "this:replace"
      :hx-vals "js:{page: event.type==='load' ? (document.querySelector('[data-part-page]')?.value || '1') : '1', 'table-scroll': event.type==='load' ? (document.querySelector('#part-table-position')?.value || '0') : '0'}"
      :hx-trigger "load, change[target.tagName === 'SELECT'], search, keyup changed delay:300ms"}
     [:label.filters__field "Bundle" [:select {:name "bundle"} (http-views/options "All bundles" (:bundles facets))]]
     [:label.filters__field "Class" [:select {:name "class"} (http-views/options "All classes" (:classes facets))]]
     [:label.filters__field "Role" [:select {:name "role"} (http-views/options "All roles" (map name (:roles facets)))]]
     [:label.filters__field "Variant"
      [:select {:name "variant"}
       [:option {:value ""} (if import-session "All variants" "Browsable parts")]
       (when-not import-session [:option {:value "all"} "All variants"])
       [:option {:value "unsupported"} "Unsupported"]
       [:option {:value "supported"} "Supported"]
       [:option {:value "unsupported-pitted"} "Unsupported (pitted / recessed)"]]]
     [:label.filters__field "Orientation" [:select {:name "orientation"}
                                           [:option {:value "all"} "Any orientation"]
                                           [:option {:value "unset"} "Orientation unset"]
                                           [:option {:value "saved"} "Orientation saved"]]]
     [:label.filters__field "Name" [:input {:type "search" :name "q" :placeholder "Search names"}]]
     [:button (merge selection-attrs {:type "button" :data-select-all "none" :hx-trigger "click"
                                      :hx-vals "{\"selection\":\"none\"}"}) "Clear selection"]
     (when-not import-session
       [:fieldset.filters__variants
        [:legend "Variant availability"]
        (for [[field variant label] parts/variant-filters]
          [:label.filters__field label
           [:select {:name field :title (when (= variant :unsupported-pitted) "Unsupported (pitted / recessed)")}
            [:option {:value ""} "Any"]
            [:option {:value "available"} "Available"]
            [:option {:value "missing"} "Missing"]]])])]
    [:form#part-open (merge workspace-views/transition-attrs
                            {:hidden true :method "get" :action "/workspace/browse" :hx-get "/workspace/browse" :hx-target "#detail" :hx-swap "innerHTML settle:0ms"
                             :hx-include "#bulk-orient-filters, #part-table-position, [data-part-page]"})
     [:input#part-open-id {:type "hidden" :name "part-id"}]]
    [:div#bulk-orient-results.bulk-orient__results
     [:input {:type "hidden" :name "page" :value "1" :data-part-page true}]
     [:input {:id "part-table-position" :type "hidden" :name "table-scroll" :value "0"}]
     [:p.muted "Loading parts…"]]
    (selection-form selection (some? import-session))
    (vocabulary/choices (:values facets))]))

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
