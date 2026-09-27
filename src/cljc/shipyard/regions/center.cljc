(ns shipyard.regions.center
  "Estimate an axis-aligned symmetry plane from uniformly sampled outer surfaces."
  (:require [shipyard.regions.symmetry :as symmetry]))

(def ^:private resolution 64)

(defn- sample-triangle [samples [a b c]]
  (let [[ax ay az] a [bx by bz] b [cx cy cz] c
        ux (- bx ax) uy (- by ay) vx (- cx ax) vy (- cy ay)
        determinant (- (* ux vy) (* vx uy))]
    (if (< (abs determinant) 1e-12) samples
        (let [start-x (max 0 (int (Math/ceil (- (min ax bx cx) 0.5))))
              end-x (min resolution (inc (int (Math/floor (- (max ax bx cx) 0.5)))))
              start-y (max 0 (int (Math/ceil (- (min ay by cy) 0.5))))
              end-y (min resolution (inc (int (Math/floor (- (max ay by cy) 0.5)))))]
          (reduce (fn [samples [x y]]
                    (let [dx (- (+ x 0.5) ax) dy (- (+ y 0.5) ay)
                          s (/ (- (* dx vy) (* dy vx)) determinant)
                          t (/ (- (* ux dy) (* uy dx)) determinant)]
                      (if (and (>= s -1e-9) (>= t -1e-9) (<= (+ s t) (+ 1 1e-9)))
                        (let [z (+ az (* s (- bz az)) (* t (- cz az)))
                              index (+ x (* resolution y))
                              [lo hi] (or (get samples index) [z z])]
                          (assoc! samples index [(min lo z) (max hi z)]))
                        samples)))
                  samples (for [x (range start-x end-x) y (range start-y end-y)] [x y]))))))

(defn estimate
  "Median midpoint of outer intersections along a 64×64 ray grid. Uniform
  projected-area sampling avoids vertex/tessellation bias; the median resists
  small asymmetric details. Degenerate/open sheets fall back to bounds center.
  Triangles and bounds must use the same canonical coordinates."
  [triangles [lo hi :as bounds] axis]
  (let [k ({:x 0 :y 1 :z 2} axis)
        [u v] (remove #{k} (range 3))
        width (- (nth hi u) (nth lo u)) height (- (nth hi v) (nth lo v))
        fallback (symmetry/center-offset bounds axis)]
    (if (or (not (pos? width)) (not (pos? height))) fallback
        (let [project (fn [p] [(* resolution (/ (- (nth p u) (nth lo u)) width))
                               (* resolution (/ (- (nth p v) (nth lo v)) height))
                               (nth p k)])
              samples (persistent!
                       (reduce (fn [samples triangle] (sample-triangle samples (mapv project triangle)))
                               (transient {}) triangles))
              epsilon (* 1e-9 (- (nth hi k) (nth lo k)))
              centers (vec (sort (keep (fn [[low high]]
                                         (when (> (- high low) epsilon) (/ (+ low high) 2.0)))
                                       (vals samples))))
              n (count centers)]
          (if (zero? n) fallback
              (/ (+ (nth centers (quot (dec n) 2)) (nth centers (quot n 2))) 2.0))))))
