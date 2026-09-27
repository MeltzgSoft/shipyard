(ns shipyard.regions.colors
  "Deterministic allocation of separated region-identification colors.")

(def builtins [[0.6 0.65 0.7] [0.15 0.6 0.95]])

(defn valid? [rgb]
  (and (vector? rgb) (= 3 (count rgb)) (every? #(and (number? %) (<= 0 % 1)) rgb)))

(defn- oklab [[r g b]]
  (let [[r g b] (mapv #(if (<= % 0.04045) (/ % 12.92) (Math/pow (/ (+ % 0.055) 1.055) 2.4)) [r g b])
        l (Math/pow (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b)) (/ 1.0 3))
        m (Math/pow (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b)) (/ 1.0 3))
        s (Math/pow (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b)) (/ 1.0 3))]
    [(+ (* 0.2104542553 l) (* 0.793617785 m) (* -0.0040720468 s))
     (+ (* 1.9779984951 l) (* -2.428592205 m) (* 0.4505937099 s))
     (+ (* 0.0259040371 l) (* 0.7827717662 m) (* -0.808675766 s))]))

(defn- squared-distance [a b]
  (reduce + (map (fn [x y] (let [d (- x y)] (* d d))) a b)))

(defn distance [a b]
  (Math/sqrt (squared-distance (oklab a) (oklab b))))

(defn choose
  "Maximize separation from all assigned colors in OKLab. The candidate grid
  grows with the registry; persisted choices never depend on subsequent edits."
  [assigned]
  (let [used (mapv oklab (concat builtins assigned))
        steps (+ 4 (quot (count assigned) 32))
        channels (mapv #(+ 0.12 (* 0.84 (/ % steps))) (range (inc steps)))
        candidates (for [r channels g channels b channels
                         :let [rgb [r g b] lab (oklab rgb)]
                         :when (<= 0.48 (first lab) 0.9)]
                     [rgb (apply min (map #(squared-distance lab %) used))])]
    (first (apply max-key second candidates))))
