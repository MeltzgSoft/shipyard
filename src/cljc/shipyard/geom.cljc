(ns shipyard.geom
  "Pure source-space assembly matrices. Column-major, column vectors, millimeters."
  (:require [shipyard.math :as math]
            [shipyard.part.orientation :as orientation]))

(def identity-matrix [1.0 0.0 0.0 0.0
                      0.0 1.0 0.0 0.0
                      0.0 0.0 1.0 0.0
                      0.0 0.0 0.0 1.0])

(def tolerance 1e-9)
(def mount-alignment-tolerance 0.02)

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

(defn- yaw-matrix
  "A right-handed world-space rotation around the fixed global +Y axis."
  [radians]
  (let [cosine (#?(:clj Math/cos :cljs js/Math.cos) radians)
        sine (#?(:clj Math/sin :cljs js/Math.sin) radians)]
    [cosine 0.0 (- sine) 0.0
     0.0 1.0 0.0 0.0
     sine 0.0 cosine 0.0
     0.0 0.0 0.0 1.0]))

(defn- transform-direction [matrix direction]
  (math/subtract (transform-point matrix direction)
                 (transform-point matrix [0.0 0.0 0.0])))

(defn- horizontal-length [[x _ z]]
  (math/length [x z]))

(defn- heading [x z]
  (#?(:clj Math/atan2 :cljs js/Math.atan2) x z))

(defn- forward-aligning-yaw
  "The global-Y turn that points the child toward the parent's forward heading.

  A forward vector with no horizontal component has no meaningful yaw, so the
  child's configured heading remains unchanged."
  [parent-forward child-forward]
  (let [[px _ pz] parent-forward
        [cx _ cz] child-forward
        parent-horizontal (horizontal-length parent-forward)
        child-horizontal (horizontal-length child-forward)]
    (if (or (<= parent-horizontal mount-alignment-tolerance)
            (<= child-horizontal mount-alignment-tolerance))
      0.0
      (- (heading px pz) (heading cx cz)))))

(defn- face-aligning-yaw
  "Return the global-Y turn that makes child and parent face normals oppose.

  A near-vertical join has no meaningful yaw, so preserve the configured
  orientation. A non-vertical pair must have compatible vertical components;
  otherwise a Y-only assembly rotation cannot make its faces mate."
  [parent-axis child-axis parent-forward child-forward]
  (let [[px py pz] parent-axis
        [cx cy cz] child-axis
        parent-horizontal (horizontal-length parent-axis)
        child-horizontal (horizontal-length child-axis)
        close? #(<= (abs (double %)) mount-alignment-tolerance)]
    (cond
      (and (close? parent-horizontal) (close? child-horizontal))
      (forward-aligning-yaw parent-forward child-forward)
      (or (close? parent-horizontal) (close? child-horizontal)
          (not (close? (+ py cy)))
          (not (close? (- parent-horizontal child-horizontal))))
      (throw (ex-info "The parent and child mount faces cannot align using a global-Y rotation."
                      {:code :incompatible-mount-orientation
                       :parent-axis parent-axis :child-axis child-axis}))
      :else
      (- (heading (- px) (- pz)) (heading cx cz)))))

(defn attachment-matrix
  "Place a child mount on a parent mount while preserving its configured pose.

  The child's saved source-to-canonical orientation is retained. Assembly adds
  only the global-Y rotation needed to make the two outward mount normals
  oppose. Vertical mount faces leave yaw unconstrained, so their child instead
  inherits the assembled parent's forward heading. The child is then translated
  to the parent mount."
  ([parent parent-mount child-mount]
   (attachment-matrix parent parent-mount child-mount orientation/identity-quaternion 0.0))
  ([parent parent-mount child-mount gap]
   (attachment-matrix parent parent-mount child-mount orientation/identity-quaternion gap))
  ([parent parent-mount child-mount child-orientation gap]
   (when-not (math/finite-number? gap)
     (throw (ex-info "Mount gap must be finite." {:code :invalid-gap})))
   (frame-matrix parent-mount)
   (frame-matrix child-mount)
   (let [child-orientation (orientation-matrix child-orientation)
         parent-pos (transform-point parent (:mount/pos parent-mount))
         parent-axis (math/normalize
                      (transform-direction parent (:mount/axis parent-mount)))
         child-axis (math/normalize
                     (transform-direction child-orientation (:mount/axis child-mount)))
         parent-forward (math/normalize (transform-direction parent [0.0 0.0 1.0]))
         child-forward (math/normalize (transform-direction child-orientation [0.0 0.0 1.0]))
         child-pose (multiply (yaw-matrix (face-aligning-yaw parent-axis child-axis
                                                             parent-forward child-forward))
                              child-orientation)
         target-pos (math/add parent-pos (math/scale gap parent-axis))
         child-offset (transform-point child-pose (:mount/pos child-mount))
         [x y z] (math/subtract target-pos child-offset)]
     (assoc child-pose 12 x 13 y 14 z))))
