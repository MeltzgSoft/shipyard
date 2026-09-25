(ns shipyard.regions.symmetry
  "Geometric reflection of triangle selections in canonical part coordinates.
  A bounding-volume tree keeps queries local; overlap permits different tessellation."
  (:require [shipyard.math :as math]
            [shipyard.part.orientation :as orientation]))

(defn- min3 [a b]
  [(min (nth a 0) (nth b 0)) (min (nth a 1) (nth b 1)) (min (nth a 2) (nth b 2))])

(defn- max3 [a b]
  [(max (nth a 0) (nth b 0)) (max (nth a 1) (nth b 1)) (max (nth a 2) (nth b 2))])

(defn- bounds [[a b c]]
  [(min3 (min3 a b) c) (max3 (max3 a b) c)])

(defn- merge-bounds [entries]
  (reduce (fn [[lo hi] entry]
            (let [[a b] (:bounds entry)] [(min3 lo a) (max3 hi b)]))
          (:bounds (first entries)) (rest entries)))

(defn- tree [entries]
  (when (seq entries)
    (let [[lo hi :as box] (merge-bounds entries)]
      (if (<= (count entries) 12)
        {:bounds box :entries entries}
        (let [span (math/subtract hi lo)
              axis (apply max-key #(nth span %) (range 3))
              middle (/ (+ (nth lo axis) (nth hi axis)) 2)
              [left right] (reduce (fn [[left right] entry]
                                     (if (< (nth (:center entry) axis) middle)
                                       [(conj left entry) right] [left (conj right entry)]))
                                   [[] []] entries)
              ;; Coincident centroids cannot be separated spatially.
              [left right] (if (or (empty? left) (empty? right))
                             (let [half (quot (count entries) 2)]
                               [(subvec entries 0 half) (subvec entries half)])
                             [left right])]
          {:bounds box :left (tree left) :right (tree right)})))))

(defn index
  "Build once per source mesh and part orientation; triangle ordinals stay stable."
  [triangles quaternion]
  (let [quaternion (orientation/orientation-of quaternion)
        triangles (if (= quaternion orientation/identity-quaternion) (vec triangles)
                      (mapv #(mapv (partial orientation/rotate-vector quaternion) %) triangles))
        entries (mapv (fn [i points]
                        {:id i :points points :bounds (bounds points)
                         :center (math/scale (/ 1.0 3) (reduce math/add points))})
                      (range) triangles)
        root (tree entries)]
    {:triangles triangles :tree root
     :epsilon (if root (max 1e-6 (* 1e-6 (math/length (apply math/subtract (reverse (:bounds root)))))) 1e-6)}))

(defn center-offset [index axis]
  (let [coordinate ({:x 0 :y 1 :z 2} axis)
        [lo hi] (get-in index [:tree :bounds])]
    (if (and coordinate lo) (/ (+ (nth lo coordinate) (nth hi coordinate)) 2.0) 0.0)))

(defn- intersects? [[a b] [c d] epsilon]
  (and (<= (nth a 0) (+ (nth d 0) epsilon)) (<= (nth c 0) (+ (nth b 0) epsilon))
       (<= (nth a 1) (+ (nth d 1) epsilon)) (<= (nth c 1) (+ (nth b 1) epsilon))
       (<= (nth a 2) (+ (nth d 2) epsilon)) (<= (nth c 2) (+ (nth b 2) epsilon))))

(defn- candidates [node box epsilon]
  (when (and node (intersects? (:bounds node) box epsilon))
    (if-let [entries (:entries node)] (filter #(intersects? (:bounds %) box epsilon) entries)
            (concat (candidates (:left node) box epsilon) (candidates (:right node) box epsilon)))))

;; Fixed-size arithmetic avoids sequence allocation in the per-candidate hot path.
(defn- subtract3 [a b]
  [(- (nth a 0) (nth b 0)) (- (nth a 1) (nth b 1)) (- (nth a 2) (nth b 2))])

(defn- dot3 [a b]
  (+ (* (nth a 0) (nth b 0)) (* (nth a 1) (nth b 1)) (* (nth a 2) (nth b 2))))

(defn- unit3 [v]
  (let [length (#?(:clj Math/sqrt :cljs js/Math.sqrt) (dot3 v v))]
    (when (> length 1e-12)
      [(/ (nth v 0) length) (/ (nth v 1) length) (/ (nth v 2) length)])))

(defn- normal [[a b c]]
  (unit3 (math/cross (subtract3 b a) (subtract3 c a))))

(defn- interval-overlap? [a b axis epsilon]
  (let [a0 (dot3 axis (nth a 0)) a1 (dot3 axis (nth a 1)) a2 (dot3 axis (nth a 2))
        b0 (dot3 axis (nth b 0)) b1 (dot3 axis (nth b 1)) b2 (dot3 axis (nth b 2))]
    (> (- (min (max a0 a1 a2) (max b0 b1 b2))
          (max (min a0 a1 a2) (min b0 b1 b2))) epsilon)))

(defn- overlaps? [a na b epsilon]
  (when-let [nb (normal b)]
    (when (and (> (dot3 na nb) 0.99999)
               (every? #(<= (abs (dot3 na (subtract3 % (first a)))) epsilon) b))
      ;; Separating-axis test in the common plane. Strict interval overlap
      ;; excludes faces that only touch along a vertex or edge.
      (every? (fn [points]
                (every? (fn [i]
                          (let [p (nth points i) q (nth points (mod (inc i) 3))
                                axis (unit3 (math/cross na (subtract3 q p)))]
                            (interval-overlap? a b axis epsilon)))
                        (range 3)))
              [a b]))))

(defn counterparts
  "Return triangles overlapping the reflected face, with matching surface direction.
  Missing/asymmetric geometry has no counterpart; no distant face is guessed."
  [{:keys [triangles tree epsilon] :as index} axis offset triangle]
  (if-let [[a b c] (get triangles triangle)]
    (let [offset (or offset (center-offset index axis))
          ;; Reflection reverses winding; reverse it back to preserve the outward normal.
          coordinate ({:x 0 :y 1 :z 2} axis)
          reflected (mapv #(assoc % coordinate (- (* 2 offset) (nth % coordinate))) [a c b])]
      (if-let [normal (normal reflected)]
        (into #{} (keep (fn [{:keys [id points]}] (when (overlaps? reflected normal points epsilon) id)))
              (candidates tree (bounds reflected) epsilon))
        #{}))
    #{}))

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
