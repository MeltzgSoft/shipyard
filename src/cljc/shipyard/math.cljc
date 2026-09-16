(ns shipyard.math
  "Pure numeric and vector operations shared by the JVM and browser.")

(defn finite-number?
  "True when `value` is a finite number on the current runtime."
  [value]
  (and (number? value)
       #?(:clj  (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn parse-finite-double
  "Parse `value` as a finite double, returning nil for invalid input."
  [value]
  (try
    (let [number #?(:clj  (Double/parseDouble (str value))
                    :cljs (js/Number value))]
      (when (finite-number? number) number))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn add [a b]
  (mapv + a b))

(defn subtract [a b]
  (mapv - a b))

(defn scale [factor values]
  (mapv #(* factor %) values))

(defn dot [a b]
  (reduce + (map * a b)))

(defn cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn length [values]
  (#?(:clj Math/sqrt :cljs js/Math.sqrt) (dot values values)))

(defn normalize
  "Return a finite unit vector longer than epsilon, scaling before squaring."
  ([values] (normalize values 0.0))
  ([values epsilon]
   (when (and (vector? values) (every? finite-number? values)
              (finite-number? epsilon) (not (neg? epsilon)))
     (let [largest (reduce max 0.0 (map #(abs (double %)) values))]
       (when (pos? largest)
         (let [scaled (mapv #(/ (double %) largest) values)
               magnitude (length scaled)]
           ;; Compare without constructing the possibly overflowing true length.
           (when (> largest (/ epsilon magnitude))
             (mapv #(/ % magnitude) scaled))))))))

(defn project-onto-plane [axis vector]
  (subtract vector (scale (dot axis vector) axis)))
