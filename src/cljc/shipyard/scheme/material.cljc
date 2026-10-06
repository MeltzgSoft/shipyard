(ns shipyard.scheme.material
  "Pure effective material resolution shared by server and viewport.")

(def neutral {:base [(/ 154.0 255) (/ 164.0 255) (/ 175.0 255)] :metalness 0.05 :roughness 0.65})

(defn effective-profile
  "Project a ship's selected fleet palette without copying it into custom paint."
  [profile]
  (cond-> (dissoc profile :scheme/base)
    (contains? profile :scheme/base) (assoc :scheme/layers (get-in profile [:scheme/base :scheme/layers]))))

(defn resolve-material [profile]
  (or (get-in (effective-profile profile) [:scheme/layers "Primary"]) neutral))

(defn srgb->linear [value]
  (if (<= value 0.04045)
    (/ value 12.92)
    (#?(:clj Math/pow :cljs js/Math.pow) (/ (+ value 0.055) 1.055) 2.4)))
