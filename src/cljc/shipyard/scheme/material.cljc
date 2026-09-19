(ns shipyard.scheme.material
  "Pure effective material resolution shared by server and viewport.")

(def neutral {:base [(/ 154.0 255) (/ 164.0 255) (/ 175.0 255)] :metalness 0.05 :roughness 0.65})

(defn resolve-material [scheme path part-id role]
  (let [instance (get-in scheme [:scheme/instances path])]
    (or (when (= part-id (:part-id instance)) (:material instance))
        (get-in scheme [:scheme/roles role]) neutral)))

(defn select-scheme [schemes override fleet-default]
  (let [id (or override fleet-default)]
    {:id id :scheme (get schemes id) :missing? (boolean (and id (not (contains? schemes id))))}))

(defn srgb->linear [value]
  (if (<= value 0.04045)
    (/ value 12.92)
    (#?(:clj Math/pow :cljs js/Math.pow) (/ (+ value 0.055) 1.055) 2.4)))
