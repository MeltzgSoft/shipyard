(ns shipyard.regions.dom
  "One parsed, source-bound region snapshot per server-rendered panel."
  (:require [cljs.reader :as edn]))

(defn regions! [part-id mesh-key]
  (when-let [panel (.getElementById js/document "part-regions")]
    (when (and (= part-id (.getAttribute panel "data-part-id"))
               (= mesh-key (.getAttribute panel "data-mesh-key")))
      (let [text [(.getAttribute panel "data-regions") (.getAttribute panel "data-region-faces")]
            cached (.-shipyardRegions panel)]
        (if (= text (:text cached)) (:regions cached)
            (let [regions (assoc (edn/read-string (first text))
                                 :faces (js->clj (js/JSON.parse (second text))))]
              (set! (.-shipyardRegions panel) {:text text :regions regions})
              regions))))))
