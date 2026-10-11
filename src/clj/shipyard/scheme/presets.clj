(ns shipyard.scheme.presets
  "Shared named colors in the durable metadata store."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [malli.core :as m]
            [shipyard.domain.schemas :as schemas]
            [shipyard.store.db :as store]
            [shipyard.scheme.color :as color]
            [shipyard.http.htmx :as htmx]))

(defn records! [{:keys [store]}]
  (store/read! store
               (fn [db]
                 (mapv #(d/pull db [:color-preset/hex :color-preset/name] [:color-preset/hex %])
                       (sort (d/q '[:find [?hex ...] :where [_ :color-preset/hex ?hex]] db))))))

(defn colors! [schemes] (mapv :color-preset/hex (records! schemes)))

(defn normalize [hex name]
  {:color-preset/hex (str/lower-case hex) :color-preset/name (str/trim name)})

(def ^:private preset? (m/validator schemas/color-preset))

(defn change! [{:keys [store]} operation hex name]
  (when-not (color/valid-hex? hex) (throw (ex-info "Invalid preset color" {})))
  (let [record (when (= operation :add) (normalize hex name))]
    (when (and record (not (preset? record))) (throw (ex-info "Invalid preset name" {})))
    (store/write! store
                  (fn [conn]
                    (let [hex (str/lower-case hex)]
                      (case operation
                        :add (d/transact! conn [record])
                        :remove (when (d/entid @conn [:color-preset/hex hex])
                                  (d/transact! conn [[:db.fn/retractEntity [:color-preset/hex hex]]]))))))))

(defn save-control []
  [:div.color-preset-save
   [:label "Color name"
    [:input {:name "name" :form "color-preset-save" :required true :maxlength 200
             :pattern ".*\\S.*" :onchange "event.stopPropagation()"}]]
   [:button {:type "submit" :form "color-preset-save"} "Save current color"]])

(defn panel [records error hex]
  [:section#scheme-presets
   [:h3 "Saved colors"]
   (when error [:p.detail__error {:role "alert"} error])
   [:form#color-preset-save {:method "post" :action "/ships/schemes/presets/add" :hx-post "/ships/schemes/presets/add"
                             :hx-target "#scheme-presets" :hx-swap "outerHTML" :hx-sync "#detail:queue last"
                             :hx-vals "js:{base:document.querySelector('#scheme-material, #paint-brush').querySelector('[data-color-value]').value}"}
    [:input {:type "hidden" :name "base" :value hex}]]
   [:div.color-presets
    (for [{:color-preset/keys [hex name]} records
          :let [name (or name hex)]]
      [:div.color-preset
       [:button.color-preset__color {:type "button" :data-color-preset hex :aria-label (str "Use " name)
                                     :title (str name " (" hex ")") :style (str "background-color:" hex)}]
       [:span.color-preset__name {:title name} name]
       [:form {:method "post" :action "/ships/schemes/presets/remove" :hx-post "/ships/schemes/presets/remove"
               :hx-target "#scheme-presets" :hx-swap "outerHTML" :hx-sync "#detail:queue last"}
        [:button.color-preset__remove {:type "submit" :name "base" :value hex :aria-label (str "Remove " name)} "×"]]])]])

(defn handle! [{:keys [schemes]} operation request]
  (let [{:keys [base name]} (get-in request [:parameters :form])
        error (try (change! schemes operation base name) nil
                   (catch Exception _ "Could not save color presets. Try again."))]
    (htmx/fragment (panel (records! schemes) error base))))
