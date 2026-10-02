(ns shipyard.file-picker.views
  "Desktop chooser buttons beside ordinary path fields; no browser filesystem UI.")

(def fields
  {"settings-root" {:id "settings-root" :name "root" :label "Library folder" :kind "directory"}
   "setup-root" {:id "setup-root" :name "root" :label "Library folder" :kind "directory"}
   "import-archive" {:id "import-archive" :name "archive" :label "ZIP archive" :kind "zip"}})

(defn path-input [{:keys [id name kind]} value]
  [:input {:id id :name name :value (or value "") :type "text" :data-picker-value true
           :autocomplete "off" :spellcheck "false"
           :placeholder (if (= kind "directory") "Choose a folder or enter its path" "Choose a ZIP or enter its path")}])

(defn field
  [{:keys [id value]}]
  (let [{:keys [label] :as config} (get fields id)]
    [:div.file-picker
     [:label {:for id} label]
     [:div.file-picker__selection
      (path-input config value)
      [:button {:type "button" :data-picker-browse true :aria-label (str "Browse for " label)
                :hx-post (str "/files/choose/" id) :hx-target (str "#" id "-picker-message")
                :hx-swap "innerHTML" :hx-sync "this:drop" :hx-disabled-elt "this"}
       "Browse…"]]
     [:p.file-picker__message {:id (str id "-picker-message") :role "status"}]]))

(defn selected [field path]
  (update (path-input (get fields field) path) 1 assoc :hx-swap-oob "outerHTML"))
