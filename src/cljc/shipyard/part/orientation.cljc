(ns shipyard.part.orientation
  "Pure part-orientation math shared by the server and Three.js viewport."
  (:require [clojure.string :as str]))

(def identity-quaternion [0.0 0.0 0.0 1.0])

(def ^:private epsilon 1.0e-9)
(def ^:private max-degrees 36000.0)

(defn- finite-number? [x]
  (and (number? x)
       #?(:clj  (Double/isFinite (double x))
          :cljs (js/Number.isFinite x))))

(defn- length-squared [values]
  (reduce + (map #(* % %) values)))

(defn- normalize-vector [values]
  (when (and (vector? values) (every? finite-number? values))
    (let [length (#?(:clj Math/sqrt :cljs js/Math.sqrt) (length-squared values))]
      (when (> length epsilon)
        (mapv #(/ % length) values)))))

(defn normalize-quaternion [q]
  (when (and (vector? q) (= 4 (count q)))
    (normalize-vector q)))

(defn orientation-of [part-orientation]
  (or (normalize-quaternion part-orientation) identity-quaternion))

(defn quaternion-multiply
  "Hamilton product. `a * b` applies `b` first, then `a`."
  [[ax ay az aw] [bx by bz bw]]
  [(+ (* aw bx) (* ax bw) (* ay bz) (- (* az by)))
   (+ (* aw by) (- (* ax bz)) (* ay bw) (* az bx))
   (+ (* aw bz) (* ax by) (- (* ay bx)) (* az bw))
   (+ (* aw bw) (- (* ax bx)) (- (* ay by)) (- (* az bz)))])

(defn- axis-quaternion [[x y z] degrees]
  (let [half-radians (* degrees (/ #?(:clj Math/PI :cljs js/Math.PI) 360.0))
        sine (#?(:clj Math/sin :cljs js/Math.sin) half-radians)
        cosine (#?(:clj Math/cos :cljs js/Math.cos) half-radians)]
    [(* x sine) (* y sine) (* z sine) cosine]))

(defn from-euler-degrees
  "Build a quaternion using Three.js-compatible YXZ order: yaw around +Y,
  pitch around +X, then roll around +Z."
  [yaw pitch roll]
  (normalize-quaternion
   (quaternion-multiply
    (quaternion-multiply (axis-quaternion [0.0 1.0 0.0] yaw)
                         (axis-quaternion [1.0 0.0 0.0] pitch))
    (axis-quaternion [0.0 0.0 1.0] roll))))

(defn- clamp [x low high]
  (max low (min high x)))

(defn to-euler-degrees
  "Return `[yaw pitch roll]` for a normalized quaternion in YXZ order."
  [orientation]
  (let [[x y z w] (orientation-of orientation)
        xx (* x x)
        yy (* y y)
        zz (* z z)
        m11 (- 1.0 (* 2.0 (+ yy zz)))
        m13 (* 2.0 (+ (* x z) (* w y)))
        m21 (* 2.0 (+ (* x y) (* w z)))
        m22 (- 1.0 (* 2.0 (+ xx zz)))
        m23 (* 2.0 (- (* y z) (* w x)))
        m31 (* 2.0 (- (* x z) (* w y)))
        m33 (- 1.0 (* 2.0 (+ xx yy)))
        pitch (#?(:clj Math/asin :cljs js/Math.asin) (- (clamp m23 -1.0 1.0)))
        [yaw roll] (if (< (#?(:clj Math/abs :cljs js/Math.abs) m23) 0.9999999)
                     [(#?(:clj Math/atan2 :cljs js/Math.atan2) m13 m33)
                      (#?(:clj Math/atan2 :cljs js/Math.atan2) m21 m22)]
                     [(#?(:clj Math/atan2 :cljs js/Math.atan2) (- m31) m11)
                      0.0])
        degrees #(* % (/ 180.0 #?(:clj Math/PI :cljs js/Math.PI)))]
    (mapv degrees [yaw pitch roll])))

(defn inverse [orientation]
  (let [[x y z w] (orientation-of orientation)]
    [(- x) (- y) (- z) w]))

(defn relative-orientation
  "Return the rotation from `saved` to `candidate`."
  [saved candidate]
  (orientation-of
   (quaternion-multiply (orientation-of candidate)
                        (inverse saved))))

(defn rotate-vector
  "Rotate a three-vector by `orientation`."
  [orientation [vx vy vz]]
  (let [[qx qy qz qw] (orientation-of orientation)
        tx (* 2.0 (- (* qy vz) (* qz vy)))
        ty (* 2.0 (- (* qz vx) (* qx vz)))
        tz (* 2.0 (- (* qx vy) (* qy vx)))]
    [(+ vx (* qw tx) (- (* qz ty)) (* qy tz))
     (+ vy (* qw ty) (- (* qx tz)) (* qz tx))
     (+ vz (* qw tz) (- (* qy tx)) (* qx ty))]))

(defn oriented-bounds
  "Return the axis-aligned bounds after rotating a source-space bounding box."
  [bbox-min bbox-max part-orientation]
  (let [[min-x min-y min-z] bbox-min
        [max-x max-y max-z] bbox-max
        corners (for [x [min-x max-x]
                      y [min-y max-y]
                      z [min-z max-z]]
                  (rotate-vector part-orientation [x y z]))]
    [(mapv (fn [idx] (apply min (map #(nth % idx) corners))) (range 3))
     (mapv (fn [idx] (apply max (map #(nth % idx) corners))) (range 3))]))

(defn- dot [a b]
  (reduce + (map * a b)))

(defn- subtract [a b]
  (mapv - a b))

(defn- scale [factor values]
  (mapv #(* factor %) values))

(defn- project-onto-plane [axis vector]
  (subtract vector (scale (dot axis vector) axis)))

(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn canonical-mount-roll
  "Derive mount +X so mount +Y follows canonical part up. If the selected
  normal is vertical, canonical forward becomes mount +Y instead."
  [part-orientation axis]
  (when-let [axis (normalize-vector axis)]
    (let [canonical->source (inverse part-orientation)
          source-directions (map #(rotate-vector canonical->source %)
                                 [[0.0 1.0 0.0]
                                  [0.0 0.0 1.0]
                                  [1.0 0.0 0.0]])]
      (some (fn [desired-up]
              (when-let [mount-up (normalize-vector (project-onto-plane axis desired-up))]
                (normalize-vector (cross mount-up axis))))
            source-directions))))

(defn orient-mount-frame [frame part-orientation]
  (if-let [roll (canonical-mount-roll part-orientation (:mount/axis frame))]
    (assoc frame :mount/roll roll)
    frame))

(defn- source-plane-normal [part-orientation plane]
  (some->> (case plane
             :x [1.0 0.0 0.0]
             :y [0.0 1.0 0.0]
             :z [0.0 0.0 1.0]
             nil)
           (rotate-vector (inverse part-orientation))))

(defn reflect-position
  "Reflect a source-space point across a canonical part plane at `offset`."
  [part-orientation plane offset point]
  (when-let [normal (source-plane-normal part-orientation plane)]
    (subtract point (scale (* 2.0 (- (dot normal point) offset)) normal))))

(defn reflect-direction
  "Reflect a source-space direction across a canonical part plane."
  [part-orientation plane direction]
  (when-let [normal (source-plane-normal part-orientation plane)]
    (subtract direction (scale (* 2.0 (dot normal direction)) normal))))

(defn plane-distance
  "Signed source-point distance from a canonical part plane."
  [part-orientation plane offset point]
  (when-let [normal (source-plane-normal part-orientation plane)]
    (- (dot normal point) offset)))

(defn- parse-degrees [value]
  (when-not (str/blank? (str value))
    (try
      (let [number #?(:clj  (Double/parseDouble (str value))
                      :cljs (js/Number value))]
        (when (and (finite-number? number)
                   (<= (#?(:clj Math/abs :cljs js/Math.abs) number) max-degrees))
          number))
      (catch #?(:clj Exception :cljs :default) _ nil))))

(defn save-request [params]
  (case (get params "action")
    "reset" {:orientation identity-quaternion}
    "save" (let [yaw (parse-degrees (get params "part-yaw-deg"))
                 pitch (parse-degrees (get params "part-pitch-deg"))
                 roll (parse-degrees (get params "part-roll-deg"))]
             (if (every? some? [yaw pitch roll])
               {:orientation (from-euler-degrees yaw pitch roll)}
               {:error "Yaw, pitch and roll must be finite angles."}))
    {:error "Choose whether to save or reset the part orientation."}))
