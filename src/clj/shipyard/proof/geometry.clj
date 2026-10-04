(ns shipyard.proof.geometry
  "Pure source-mesh distance calculations used by proof tools."
  (:require [shipyard.math :as math]
            [shipyard.triangle :as triangle]))

(defn- point-at [^floats positions offset]
  [(double (aget positions offset))
   (double (aget positions (+ offset 1)))
   (double (aget positions (+ offset 2)))])

(defn nearest-surface
  "Return the nearest triangle, surface point, distance, and normal to `point`."
  [mesh point]
  (let [^floats positions (:positions mesh)]
    (reduce
     (fn [nearest triangle]
       (let [offset (* 9 triangle)
             a (point-at positions offset) b (point-at positions (+ offset 3)) c (point-at positions (+ offset 6))
             closest (triangle/closest-point-on-triangle point a b c)
             distance (math/length (math/subtract point closest))]
         (if (< distance (:distance-mm nearest))
           {:triangle triangle :point closest :distance-mm distance
            :normal (math/normalize (math/cross (math/subtract b a) (math/subtract c a)))}
           nearest)))
     {:distance-mm Double/POSITIVE_INFINITY}
     (range (:triangle-count mesh)))))
