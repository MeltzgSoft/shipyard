(ns shipyard.paint.shader
  "Pure extension of the pinned MeshStandardMaterial shader's PBR inputs."
  (:require [clojure.string :as str]))

(defn with-finish [vertex fragment]
  {:vertex (str "attribute vec2 shipyardFinish; varying vec2 vShipyardFinish;\n"
                (str/replace vertex "#include <begin_vertex>"
                             "#include <begin_vertex>\nvShipyardFinish = shipyardFinish;"))
   :fragment (str "uniform bool shipyardFinishEnabled; varying vec2 vShipyardFinish;\n"
                  (-> fragment
                      (str/replace "#include <roughnessmap_fragment>"
                                   "#include <roughnessmap_fragment>\nif(shipyardFinishEnabled) roughnessFactor = vShipyardFinish.y;")
                      (str/replace "#include <metalnessmap_fragment>"
                                   "#include <metalnessmap_fragment>\nif(shipyardFinishEnabled) metalnessFactor = vShipyardFinish.x;")))})
