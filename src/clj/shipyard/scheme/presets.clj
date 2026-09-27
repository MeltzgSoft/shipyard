(ns shipyard.scheme.presets
  "Shared saved colors in the durable metadata store."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [shipyard.store.db :as store]
            [shipyard.scheme.color :as color]
            [shipyard.http.htmx :as htmx]))

(defn colors! [{:keys [store]}]
  (store/read! store #(sort (d/q '[:find [?hex ...] :where [_ :color-preset/hex ?hex]] %))))

(defn change! [{:keys [store]} operation hex]
  (when-not (color/valid-hex? hex) (throw (ex-info "Invalid preset color" {})))
  (store/write! store
                (fn [conn]
                  (let [hex (str/lower-case hex)]
                    (case operation
                      :add (d/transact! conn [{:color-preset/hex hex}])
                      :remove (when (d/entid @conn [:color-preset/hex hex])
                                (d/transact! conn [[:db.fn/retractEntity [:color-preset/hex hex]]])))))))

(defn panel [schemes error]
  [:section#scheme-presets
   [:h3 "Saved colors"]
   (when error [:p.detail__error {:role "alert"} error])
   [:div.color-presets
    (for [hex (colors! schemes)]
      [:div.color-preset
       [:button.color-preset__color {:type "button" :data-color-preset hex :aria-label (str "Use " hex)
                                     :title hex :style (str "background-color:" hex)}]
       [:form {:method "post" :action "/ships/schemes/presets/remove" :hx-post "/ships/schemes/presets/remove"
               :hx-target "#scheme-presets" :hx-swap "outerHTML" :hx-sync "#detail:queue last"}
        [:button.color-preset__remove {:type "submit" :name "base" :value hex :aria-label (str "Remove " hex)} "×"]]])]
   [:form {:method "post" :action "/ships/schemes/presets/add" :hx-post "/ships/schemes/presets/add"
           :hx-target "#scheme-presets" :hx-swap "outerHTML" :hx-sync "#detail:queue last"
           :hx-include "#scheme-material input[name=base]"}
    [:button {:type "submit"} "Save current color"]]])

(defn handle! [{:keys [schemes]} operation request]
  (let [hex (get-in request [:parameters :form :base])
        error (try (change! schemes operation hex) nil
                   (catch Exception _ "Could not save color presets. Try again."))]
    (htmx/fragment (panel schemes error))))
