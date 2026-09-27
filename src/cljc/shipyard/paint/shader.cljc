(ns shipyard.paint.shader
  "Pure extension of the pinned MeshStandardMaterial shader's PBR inputs."
  (:require [clojure.string :as str]))

(defn with-finish [vertex fragment]
  {:vertex (str "attribute vec3 shipyardFinish; varying vec3 vShipyardFinish;\n"
                (str/replace vertex "#include <begin_vertex>"
                             "#include <begin_vertex>\nvShipyardFinish = shipyardFinish;"))
   :fragment (str "uniform bool shipyardFinishEnabled; varying vec3 vShipyardFinish;\n"
                  (-> fragment
                      (str/replace "#include <roughnessmap_fragment>"
                                   "#include <roughnessmap_fragment>\nif(shipyardFinishEnabled) roughnessFactor = vShipyardFinish.y;")
                      (str/replace "#include <metalnessmap_fragment>"
                                   "#include <metalnessmap_fragment>\nif(shipyardFinishEnabled) metalnessFactor = vShipyardFinish.x;")
                      (str/replace "#include <emissivemap_fragment>"
                                   "#include <emissivemap_fragment>\n#ifdef USE_COLOR\nif(shipyardFinishEnabled) totalEmissiveRadiance = diffuseColor.rgb * vShipyardFinish.z;\n#endif")))})
