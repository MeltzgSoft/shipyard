(ns shipyard.proof.geometry
  "Pure source-mesh distance calculations used by proof tools."
  (:require [shipyard.math :as math]))

(defn closest-point-on-triangle
  "Return the point on triangle `a b c` nearest to point `p`."
  [p a b c]
  (let [ab (math/subtract b a) ac (math/subtract c a) ap (math/subtract p a)
        d1 (math/dot ab ap) d2 (math/dot ac ap)]
    (cond
      (and (<= d1 0.0) (<= d2 0.0)) a
      :else
      (let [bp (math/subtract p b) d3 (math/dot ab bp) d4 (math/dot ac bp)]
        (cond
          (and (>= d3 0.0) (<= d4 d3)) b
          :else
          (let [vc (- (* d1 d4) (* d3 d2))]
            (cond
              (and (<= vc 0.0) (>= d1 0.0) (<= d3 0.0))
              (math/add a (math/scale (/ d1 (- d1 d3)) ab))
              :else
              (let [cp (math/subtract p c) d5 (math/dot ab cp) d6 (math/dot ac cp)]
                (cond
                  (and (>= d6 0.0) (<= d5 d6)) c
                  :else
                  (let [vb (- (* d5 d2) (* d1 d6))]
                    (cond
                      (and (<= vb 0.0) (>= d2 0.0) (<= d6 0.0))
                      (math/add a (math/scale (/ d2 (- d2 d6)) ac))
                      :else
                      (let [va (- (* d3 d6) (* d5 d4))]
                        (if (and (<= va 0.0) (>= (- d4 d3) 0.0) (>= (- d5 d6) 0.0))
                          (math/add b (math/scale (/ (- d4 d3)
                                                     (+ (- d4 d3) (- d5 d6)))
                                                  (math/subtract c b)))
                          (let [denom (/ 1.0 (+ va vb vc))]
                            (math/add a
                                      (math/add (math/scale (* vb denom) ab)
                                                (math/scale (* vc denom) ac)))))))))))))))))

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
             closest (closest-point-on-triangle point a b c)
             distance (math/length (math/subtract point closest))]
         (if (< distance (:distance-mm nearest))
           {:triangle triangle :point closest :distance-mm distance
            :normal (math/normalize (math/cross (math/subtract b a) (math/subtract c a)))}
           nearest)))
     {:distance-mm Double/POSITIVE_INFINITY}
     (range (:triangle-count mesh)))))
