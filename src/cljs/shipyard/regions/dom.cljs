(ns shipyard.regions.dom
  "Revision-checked region patches over a preserved source-bound DOM snapshot."
  (:require [cljs.reader :as edn]
            [shipyard.paint.projection :as projection]
            [shipyard.preparation :as preparation]))

(defn- recover! [^js panel part-id]
  (when-not (.-shipyardRecoveryPending panel)
    (set! (.-shipyardRecoveryPending panel) true)
    (when-let [status (.querySelector panel "#region-status")]
      (set! (.-textContent status) "Restoring saved regions…"))
    ;; Finish the triggering save first so its brush completion cannot cancel recovery.
    (js/setTimeout
     (fn []
       (when (identical? panel (.getElementById js/document "part-regions"))
         (.ajax (.-htmx js/window) "GET"
                (str "/parts/regions/snapshot?part-id=" (js/encodeURIComponent part-id))
                #js {:source panel :target panel :swap "outerHTML"
                     :values #js {:layer (or (some-> (.querySelector panel "#region-stroke input[name=layer]") .-value) "Secondary")
                                  :mode (or (some-> (.querySelector panel "#region-stroke input[name=mode]") .-value) "facets")
                                  :angle (or (some-> (.querySelector panel "#region-stroke input[name=angle]") .-value) "1")}}))) 0)))

(defn- apply-patch [cached patch]
  (if (contains? patch :replace)
    (assoc cached :faces (:replace patch) :projection-reset? true)
    (update cached :faces #(merge % (zipmap (:remove patch) (repeat "Primary")) (:set patch)))))

(defn- load-projection! [^js panel ^js snapshot metadata part-id mesh-key]
  (when-not (.-shipyardProjectionPending panel)
    (set! (.-shipyardProjectionPending panel) true)
    (preparation/load!
     (.getAttribute panel "data-region-projection")
     {:current? #(and (identical? panel (.getElementById js/document "part-regions"))
                      (= mesh-key (.getAttribute panel "data-mesh-key")))
      :decode! #(-> (.arrayBuffer %) (.then projection/decode))
      :ready! (fn [value]
                (if (and (= mesh-key (:mesh-key value))
                         (= (:revision-token metadata) (:region-revision value)))
                  (let [regions (merge metadata (select-keys value [:triangle-layers :layer-table]) {:faces {}})]
                    (set! (.-shipyardRegions snapshot) regions)
                    (set! (.-shipyardRegions panel) regions)
                    (.setAttribute panel "data-region-projection-ready" "true")
                    (.dispatchEvent panel (js/CustomEvent. "shipyard:region-projection" #js {:bubbles true})))
                  (recover! panel part-id)))
      :failed! #(recover! panel part-id)})))

(defn regions! [part-id mesh-key]
  (when-let [panel (.getElementById js/document "part-regions")]
    (when (and (= part-id (.getAttribute panel "data-part-id"))
               (= mesh-key (.getAttribute panel "data-mesh-key")))
      (let [snapshot (.querySelector panel "#region-snapshot")
            cached (.-shipyardRegions snapshot)]
        (if (.-shipyardRegions panel) (.-shipyardRegions panel)
            (let [metadata (edn/read-string (.getAttribute panel "data-regions"))
                  patch (some-> (.getAttribute panel "data-region-delta") edn/read-string)
                  valid? (or (nil? patch)
                             (and (= (:mesh-key cached) (:mesh-key patch))
                                  (= (:revision-token cached) (:from-token patch))))]
              (if-not valid? (do (recover! panel part-id) nil)
                      (if (and (nil? patch) (not= "true" (.getAttribute panel "data-region-empty")))
                        (do (load-projection! panel snapshot metadata part-id mesh-key) nil)
                        (let [regions (if patch (merge (apply-patch cached (:patch patch)) metadata)
                                          (assoc metadata :faces {}))]
                          (set! (.-shipyardRegions snapshot) regions)
                          (set! (.-shipyardRegions panel) regions)
                          (.setAttribute panel "data-region-projection-ready" "true")
                          regions)))))))))
