(ns shipyard.geom
  "Pure source-space assembly matrices. Column-major, column vectors, millimeters."
  (:require [shipyard.math :as math]
            [shipyard.domain.schemas :as schemas]
            [shipyard.mount.alignment :as alignment]
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
  (let [near? #(<= (abs (double %)) tolerance)]
    (boolean (and (every? schemas/valid-vec3? [pos axis roll])
                  (near? (- 1.0 (math/length axis)))
                  (near? (- 1.0 (math/length roll)))
                  (near? (math/dot axis roll))))))

(defn frame-matrix
  "Return a mount-to-source matrix, or throw an actionable authored-data error."
  [{:mount/keys [pos axis roll id] :as frame}]
  (when-not (alignment/valid? frame)
    (throw (ex-info "Reauthor the mount alignment: choose a signed horizontal or vertical axis, or none."
                    {:code :invalid-mount-alignment :mount-id id})))
  (when-not (valid-frame? frame)
    (throw (ex-info "Reauthor the mount: position must be finite and axis/roll perpendicular unit vectors."
                    {:code :invalid-frame :mount-id id :frame frame})))
  (vec (concat roll [0.0] (math/cross axis roll) [0.0] axis [0.0] pos [1.0])))

(defn- require-matrix [matrix]
  (when-not (schemas/valid-matrix? matrix)
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
  (when-not (schemas/valid-vec3? point)
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

(defn- canonical-forward-source
  "The source vector that a saved part orientation maps to canonical +Z."
  [part-orientation]
  (orientation/rotate-vector (orientation/inverse part-orientation) [0.0 0.0 1.0]))

(defn- horizontal-length [[x _ z]]
  (math/length [x z]))

(defn- heading [x z]
  (#?(:clj Math/atan2 :cljs js/Math.atan2) x z))

(defn- forward-aligning-yaw
  "The global-Y turn that points the child toward the parent's canonical heading.

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

(defn- axis-rotation [axis radians]
  (let [c (#?(:clj Math/cos :cljs js/Math.cos) radians)
        s (#?(:clj Math/sin :cljs js/Math.sin) radians)]
    (vec (concat
          (mapcat (fn [v]
                    (concat (math/add (math/scale c v)
                                      (math/add (math/scale (* (- 1.0 c) (math/dot axis v)) axis)
                                                (math/scale s (math/cross axis v)))) [0.0]))
                  [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])
          [0.0 0.0 0.0 1.0]))))

(defn- align-directions [pose parent parent-mount child-mount parent-axis]
  (let [parent-direction (alignment/direction parent-mount)
        child-direction (alignment/direction child-mount)]
    (if (and parent-direction child-direction)
      (let [a (math/normalize (math/project-onto-plane parent-axis (transform-direction pose child-direction)))
            b (math/normalize (math/project-onto-plane parent-axis (transform-direction parent parent-direction)))
            angle (#?(:clj Math/atan2 :cljs js/Math.atan2)
                   (math/dot parent-axis (math/cross a b)) (math/dot a b))]
        (multiply (axis-rotation parent-axis angle) pose))
      pose)))

(defn attachment-matrix
  "Mate source-space mounts, inheriting the parent's assembled correction.

  Normal/forward alignment retains the child's saved pose in the parent's
  canonical frame. When both mounts select an arrow, the in-plane turn
  makes their directions agree, including a half turn for opposite arrows. Translation then retains the mating point/gap."
  ([parent parent-mount child-mount]
   (attachment-matrix parent parent-mount child-mount orientation/identity-quaternion
                      orientation/identity-quaternion 0.0))
  ([parent parent-mount child-mount gap]
   (attachment-matrix parent parent-mount child-mount orientation/identity-quaternion
                      orientation/identity-quaternion gap))
  ([parent parent-mount child-mount child-orientation gap]
   (attachment-matrix parent parent-mount child-mount orientation/identity-quaternion
                      child-orientation gap))
  ([parent parent-mount child-mount parent-orientation child-orientation gap]
   (when-not (math/finite-number? gap)
     (throw (ex-info "Mount gap must be finite." {:code :invalid-gap})))
   (frame-matrix parent-mount)
   (frame-matrix child-mount)
   (let [child-pose-base (orientation-matrix child-orientation)
         parent-pos (transform-point parent (:mount/pos parent-mount))
         parent-axis (math/normalize
                      (transform-direction parent (:mount/axis parent-mount)))
         parent-pose (orientation-matrix parent-orientation)
         ;; Inherit the parent's assembly correction, including opted-in twist.
         ;; Remove its saved source pose and translation before placing a child.
         inherited (multiply (assoc parent 12 0.0 13 0.0 14 0.0)
                             (orientation-matrix (orientation/inverse
                                                  (or parent-orientation orientation/identity-quaternion))))
         local-parent-axis (transform-direction parent-pose (:mount/axis parent-mount))
         child-axis (math/normalize
                     (transform-direction child-pose-base (:mount/axis child-mount)))
         parent-forward (math/normalize
                         (transform-direction parent-pose (canonical-forward-source parent-orientation)))
         child-forward (math/normalize
                        (transform-direction child-pose-base
                                             (canonical-forward-source child-orientation)))
         child-pose (multiply inherited
                              (multiply (yaw-matrix (face-aligning-yaw local-parent-axis child-axis
                                                                       parent-forward child-forward))
                                        child-pose-base))
         child-pose (align-directions child-pose parent parent-mount child-mount parent-axis)
         target-pos (math/add parent-pos (math/scale gap parent-axis))
         child-offset (transform-point child-pose (:mount/pos child-mount))
         [x y z] (math/subtract target-pos child-offset)]
     (assoc child-pose 12 x 13 y 14 z))))
