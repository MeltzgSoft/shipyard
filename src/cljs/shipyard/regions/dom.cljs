(ns shipyard.regions.dom
  "Revision-checked region patches over a preserved source-bound DOM snapshot."
  (:require [cljs.reader :as edn]
            [shipyard.paint.delta :as delta]))

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
                                  (= (:revision cached) (:from patch))))]
              (if-not valid? (do (recover! panel part-id) nil)
                      (let [regions (assoc metadata :faces
                                           (if patch (delta/apply-patch (:faces cached) (:patch patch))
                                               (js->clj (js/JSON.parse (.getAttribute panel "data-region-faces")))))]
                        (set! (.-shipyardRegions snapshot) regions)
                        (set! (.-shipyardRegions panel) regions)
                        regions))))))))
