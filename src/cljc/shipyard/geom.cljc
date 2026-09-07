(ns shipyard.geom
  "Pure source-space assembly matrices. Column-major, column vectors, millimeters."
  (:require [shipyard.math :as math]
            [shipyard.part.orientation :as orientation]))

(def identity-matrix [1.0 0.0 0.0 0.0
                      0.0 1.0 0.0 0.0
                      0.0 0.0 1.0 0.0
                      0.0 0.0 0.0 1.0])

(def tolerance 1e-9)

(defn valid-frame?
  "A finite position and perpendicular unit +X/+Z define a right-handed frame."
  [{:mount/keys [pos axis roll]}]
  (let [vec3? #(and (vector? %) (= 3 (count %)) (every? math/finite-number? %))
        near? #(<= (abs (double %)) tolerance)]
    (boolean (and (every? vec3? [pos axis roll])
                  (near? (- 1.0 (math/length axis)))
                  (near? (- 1.0 (math/length roll)))
                  (near? (math/dot axis roll))))))

(defn frame-matrix
  "Return a mount-to-source matrix, or throw an actionable authored-data error."
  [{:mount/keys [pos axis roll id] :as frame}]
  (when-not (valid-frame? frame)
    (throw (ex-info "Reauthor the mount: position must be finite and axis/roll perpendicular unit vectors."
                    {:code :invalid-frame :mount-id id :frame frame})))
  (vec (concat roll [0.0] (math/cross axis roll) [0.0] axis [0.0] pos [1.0])))

(defn- require-matrix [matrix]
  (when-not (and (vector? matrix) (= 16 (count matrix))
                 (every? math/finite-number? matrix))
    (throw (ex-info "Expected 16 finite column-major matrix values."
                    {:code :invalid-matrix})))
  matrix)

(defn multiply
  "Compose `a * b`, applying b first."
  [a b]
  (require-matrix a)
  (require-matrix b)
  (mapv (fn [index]
          (let [row (mod index 4) column (quot index 4)]
            (reduce + (for [k (range 4)]
                        (* (nth a (+ row (* k 4)))
                           (nth b (+ k (* column 4))))))))
        (range 16)))

(defn transform-point
  "Apply an affine matrix to a source point."
  [matrix point]
  (require-matrix matrix)
  (when-not (and (vector? point) (= 3 (count point)) (every? math/finite-number? point))
    (throw (ex-info "Expected a finite three-dimensional point." {:code :invalid-point})))
  (mapv (fn [row]
          (+ (nth matrix (+ 12 row))
             (reduce + (map-indexed (fn [column value]
                                      (* (nth matrix (+ row (* column 4))) value))
                                    point))))
        (range 3)))

(defn inverse-frame
  "Inverse of an authored rigid frame; no general matrix inversion is needed."
  [{:mount/keys [pos axis roll] :as frame}]
  (frame-matrix frame)
  (let [up (math/cross axis roll)
        rows [roll up axis]]
    (vec (concat (mapcat (fn [column] (concat (map #(nth % column) rows) [0.0]))
                         (range 3))
                 (map #(- (math/dot % pos)) rows) [1.0]))))

(defn orientation-matrix
  "The root source-to-canonical pose. Missing orientation is identity; malformed is an error."
  [quaternion]
  (let [q (if (nil? quaternion)
            orientation/identity-quaternion
            (orientation/normalize-quaternion quaternion))]
    (when-not q
      (throw (ex-info "Reauthor the part orientation: expected a finite nonzero quaternion."
                      {:code :invalid-orientation})))
    (vec (concat (mapcat #(concat (orientation/rotate-vector q %) [0.0])
                         [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])
                 [0.0 0.0 0.0 1.0]))))

(defn attachment-matrix
  "Child source-to-world matrix. Parent already includes its orientation;
  child orientation cancels against its canonical plug and must not be reapplied."
  ([parent socket plug] (attachment-matrix parent socket plug 0.0))
  ([parent socket plug gap]
   (when-not (math/finite-number? gap)
     (throw (ex-info "Mount gap must be finite." {:code :invalid-gap})))
   (let [flip-and-gap [1.0 0.0 0.0 0.0
                       0.0 -1.0 0.0 0.0
                       0.0 0.0 -1.0 0.0
                       0.0 0.0 gap 1.0]]
     (-> parent
         (multiply (frame-matrix socket))
         (multiply flip-and-gap)
         (multiply (inverse-frame plug))))))
