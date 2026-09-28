(ns shipyard.scheme.editor
  "Fleet palette authoring owned by the Ship Browser workspace."
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.loadout.transforms :as loadout]
            [shipyard.loadout.model :as loadout-model]
            [shipyard.paint.transforms :as paint]
            [shipyard.paint.views :as controls]
            [shipyard.scheme.db :as schemes]
            [shipyard.scheme.material :as material]
            [shipyard.scheme.color :as color]
            [shipyard.scheme.presets :as presets]
            [shipyard.workspace.db :as workspace]))

(def attrs {:hx-target "#detail" :hx-swap "innerHTML" :hx-sync "#detail:queue last"
            :hx-disabled-elt ".scheme-editor input, .scheme-editor select, .scheme-editor button, .ship-inspector > nav button, .ship-card button"})

(defn selected! [{:keys [workspace schemes]}]
  (schemes/palette! schemes (:scheme (workspace/workspace! workspace :ships))))

(defn- color-control [hex picker]
  (let [[h s v] (color/picker-value hex picker)]
    [:div.color-picker
     [:input {:type "hidden" :name "saturation" :value s}]
     [:input {:type "hidden" :name "brightness" :value v}]
     [:div.color-spectrum {:tabindex 0 :role "slider" :aria-label "Saturation and brightness"
                           :aria-valuemin 0 :aria-valuemax 100 :aria-valuenow (* 100 s)
                           :aria-valuetext (str "Saturation " (int (* 100 s)) "%, brightness " (int (* 100 v)) "%")
                           :data-saturation s :data-brightness v
                           :style (str "--spectrum-hue:hsl(" h ",100%,50%)")}
      [:span.color-spectrum__cursor {:style (str "left:" (* 100 s) "%;top:" (* 100 (- 1 v)) "%")}]]
     [:label "Hue" [:input.color-hue {:name "hue" :type "range" :min 0 :max 359 :step 1 :value h :aria-label "Hue"}]]
     [:label "Hex color" [:input#paint-base {:type "text" :name "base" :value hex :required true
                                             :pattern "#[0-9a-fA-F]{6}" :maxlength 7 :spellcheck false
                                             :data-paint-input "true" :aria-label "Hex color"}]]]))

(defn change! [{:keys [workspace schemes catalog] {scheme-lock :lock} :schemes} action {:strs [id name layer confirmed sequence] :as params}]
  (locking scheme-lock
    (let [state (workspace/workspace! workspace :ships)
          selected (:scheme state) record (when (#{:rename :delete :material} action) (schemes/record! schemes selected))
          layers (catalog/region-layers {:registry (catalog/region-registry! catalog)})
          layer (or layer (some #{(:scheme-layer state)} layers) (first layers))
          n (some-> sequence parse-long)
          result (case action
                   :select (if (or (= "" id) (schemes/palette! schemes (parse-uuid id)))
                             (do (workspace/update-workspace! workspace :ships assoc :scheme (parse-uuid id) :scheme-sequence 0) {})
                             {:error "Choose an available scheme."})
                   :layer (if (some #{layer} layers)
                            (do (workspace/update-workspace! workspace :ships assoc :scheme-layer layer :scheme-sequence 0) {})
                            {:error "Choose an available region layer."})
                   :create (if (loadout/name? name)
                             (let [id (random-uuid) result (schemes/put! schemes {:scheme/id id :scheme/name name :scheme/layers {}} :create)]
                               (when-not (:error result) (workspace/update-workspace! workspace :ships assoc :scheme id :scheme-sequence 0)) result)
                             {:error "Enter a scheme name between 1 and 200 characters."})
                   :rename (if (and record (= id (str selected)) (loadout/name? name))
                             (schemes/put! schemes (assoc record :scheme/name name) :update)
                             {:error "Choose a scheme and enter a valid name."})
                   :delete (if (and record (= id (str selected)) (= confirmed "true"))
                             (let [result (schemes/delete! schemes selected)]
                               (when-not (:error result) (workspace/update-workspace! workspace :ships dissoc :scheme)) result)
                             {:error "Select the scheme and confirm deletion."})
                   :material (if (and record (= id (str selected)) (= layer (or (some #{(:scheme-layer state)} layers) (first layers)))
                                      n (> n (or (:scheme-sequence state) 0)) (some #{layer} layers))
                               (let [value (paint/parse-material params)]
                                 (if value
                                   (let [result (schemes/put! schemes (assoc-in record [:scheme/layers layer] value) :update)]
                                     (when-not (:error result) (workspace/update-workspace! workspace :ships assoc :scheme-sequence n
                                                                                            :scheme-picker {:scheme selected :layer layer
                                                                                                            :hsv (color/picker-value
                                                                                                                  (paint/color-hex (:base value))
                                                                                                                  (mapv #(when (string? (get params %)) (parse-double (get params %)))
                                                                                                                        ["hue" "saturation" "brightness"]))})) result)
                                   {:error "Choose a valid colour, metalness and roughness."}))
                               {:error "The scheme selection changed. Reopen it before retrying."}))]
      (when (:error result) {:error (or (:message result) (when (string? (:error result)) (:error result)) "The scheme could not be saved.")}))))

(defn panel [{:keys [workspace schemes]} database draft error]
  (let [state (workspace/workspace! workspace :ships)
        records (schemes/listing! schemes) record (schemes/palette! schemes (:scheme state))
        layers (catalog/region-layers database) layer (or (some #{(:scheme-layer state)} layers) (first layers))
        value (or (get-in record [:scheme/layers layer]) (get-in record [:scheme/layers "Primary"]) material/neutral)
        paths (mapv first (loadout-model/part-tree draft))
        local-attrs (assoc attrs :hx-target ".scheme-editor" :hx-swap "outerHTML")]
    [:div.scheme-editor
     [:h2 "Fleet schemes"]
     [:p "One palette for every ship class. Preview it here, then choose it when creating a named ship in Paint."]
     (when error [:p.detail__error {:role "alert"} error])
     [:form#scheme-select (merge attrs {:method "post" :action "/ships/schemes/select" :hx-post "/ships/schemes/select" :hx-trigger "change"})
      [:label "Scheme" [:select {:name "id"} (controls/scheme-options (vals records) (:scheme state))]]]
     [:details {:open (nil? record)} [:summary "New scheme"]
      [:form#scheme-create (merge attrs {:method "post" :action "/ships/schemes/create" :hx-post "/ships/schemes/create"})
       [:label "Scheme name" [:input {:name "name" :required true :maxlength 200}]]
       [:button {:type "submit"} "Create scheme"]]]
     (when record
       (list
        [:form#scheme-layer (merge local-attrs {:method "post" :action "/ships/schemes/layer" :hx-post "/ships/schemes/layer"})
         [:fieldset.scheme-layers
          [:legend "Region layers"]
          [:ul
           (for [id layers
                 :let [name (get-in (catalog/region-registry database) [:layers id :name] id)
                       own (get-in record [:scheme/layers id])
                       color (paint/color-hex (:base (or own (get-in record [:scheme/layers "Primary"]) material/neutral)))]]
             [:li
              [:button.scheme-layer {:type "submit" :name "layer" :value id :data-scheme-layer id
                                     :aria-label (str "Edit " name " color") :aria-pressed (str (= id layer))}
               [:span.scheme-layer__swatch {:aria-hidden "true" :style (str "background-color:" color)
                                            :data-inherits-primary (str (nil? own))}]
               [:span.scheme-layer__name name]
               (when (= id layer) [:span.scheme-layer__current "Editing"])]])]]]
        [:form#scheme-material (merge local-attrs {:method "post" :action "/ships/schemes/material" :hx-post "/ships/schemes/material" :hx-trigger "change, submit"
                                                   :aria-labelledby "scheme-material-label"
                                                   :data-paint-slots (pr-str paths) :data-paint-layer layer :data-paint-override "false"
                                                   :hx-on--config-request "var f=this.elements.sequence;f.value=Number(f.value)+1;event.detail.parameters.sequence=f.value;"})
         [:input {:type "hidden" :name "id" :value (str (:scheme/id record))}]
         [:input {:type "hidden" :name "layer" :value layer}]
         [:input {:type "hidden" :name "sequence" :value (or (:scheme-sequence state) 0)}]
         [:h3#scheme-material-label (str (get-in (catalog/region-registry database) [:layers layer :name] layer) " material")]
         (color-control (paint/color-hex (:base value))
                        (when (= [(:scheme state) layer] ((juxt :scheme :layer) (:scheme-picker state)))
                          (get-in state [:scheme-picker :hsv])))
         (controls/material-control "Metalness" "metalness" "range" (:metalness value))
         (controls/material-control "Roughness" "roughness" "range" (:roughness value))
         (controls/material-control "Glow" "glow" "range" (get value :glow 0))
         [:label "Paint name" [:input {:name "paint" :maxlength 200 :value (:paint value)}]]
         [:button {:type "submit"} "Save palette material"]]
        (presets/panel schemes nil)
        [:p#scheme-status {:role "status" :data-sequence (or (:scheme-sequence state) 0)} "Palette saved."]
        [:p.muted "Palette changes appear on every named ship using this scheme, beneath its custom paint. Mount colors must be off to see paint."]
        [:details [:summary "Manage scheme"]
         [:form#scheme-rename (merge attrs {:method "post" :action "/ships/schemes/rename" :hx-post "/ships/schemes/rename"})
          [:input {:type "hidden" :name "id" :value (str (:scheme/id record))}]
          [:label "Scheme name" [:input {:name "name" :required true :maxlength 200 :value (:scheme/name record)}]]
          [:button {:type "submit"} "Rename scheme"]]
         [:form#scheme-delete (merge attrs {:method "post" :action "/ships/schemes/delete" :hx-post "/ships/schemes/delete"
                                            :hx-confirm "Delete this fleet scheme? Named ships keep their custom paint and will show a missing-scheme warning."})
          [:input {:type "hidden" :name "id" :value (str (:scheme/id record))}]
          [:input {:type "hidden" :name "confirmed" :value "true"}]
          [:button {:type "submit"} "Delete scheme"]]]))
     (if (:hull draft)
       (list [:p (str "Preview: " (or (:class-name draft) (:name draft)) " class")]
             (controls/transfer-button "ships" "Create named ship"))
       [:p "Select a class to preview this palette."])]))
