(ns shipyard.regions.symmetry
  "Canonical mirror-plane geometry shared by the brush and its visible guide."
  (:require [shipyard.math :as math]))

(defn center-offset [[lo hi] axis]
  (let [coordinate ({:x 0 :y 1 :z 2} axis)]
    (/ (+ (nth lo coordinate) (nth hi coordinate)) 2.0)))

(defn reflection-matrix
  "Column-major world reflection for the picking pass. Reflecting geometry before
  projection selects exactly the rays of a reflected brush, including its footprint."
  [bounds axis offset]
  (let [coordinate ({:x 0 :y 1 :z 2} axis)
        offset (or offset (center-offset bounds axis))]
    (assoc [1 0 0 0, 0 1 0 0, 0 0 1 0, 0 0 0 1]
           (* 5 coordinate) -1
           (+ 12 coordinate) (* 2 offset))))

(defn plane-guide
  "Position and extent of a canonical mirror plane, using the same midpoint as painting."
  [[lo hi] axis offset]
  (let [coordinate ({:x 0 :y 1 :z 2} axis)
        center (mapv #(/ (+ %1 %2) 2.0) lo hi)
        spans (math/subtract hi lo)
        minimum (max 1e-6 (* 0.05 (math/length spans)))
        [u v] (case axis :x [2 1] :y [0 2] :z [0 1])]
    {:position (assoc center coordinate (or offset (nth center coordinate)))
     :normal (assoc [0 0 0] coordinate 1)
     :size (mapv #(* 1.2 (max minimum (nth spans %))) [u v])}))
