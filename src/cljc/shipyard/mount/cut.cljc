(ns shipyard.mount.cut
  "Physical cut definitions and source-space wireframes. Dimensions are millimetres."
  (:require [clojure.string :as str]
            [shipyard.math :as math]
            [shipyard.mount.split :as split]))

(def circle-segments 64)

(defn default-kind [kind] (if (= :plug kind) :recess :pit))

(defn request [params mount-kind]
  (let [dimension (fn [field] (when-let [value (some-> (get params field) (str/trim) (not-empty))]
                                (math/parse-finite-double value)))
        kind (keyword (or (get params "cut-kind") (name (default-kind mount-kind))))
        depth (dimension "cut-depth")
        diameter (dimension "cut-diameter")
        border (dimension "cut-border")]
    (cond
      (or (not (contains? #{"on" "true"} (get params "create-pitted"))) (= :none kind)) {}
      (not (#{:pit :recess} kind)) {:error "Choose no cut, a pit or a recess."}
      (not (and depth (pos? depth))) {:error "Cut depth must be a positive finite number in mm."}
      (and (= :pit kind) (not (and diameter (pos? diameter))))
      {:error "Pit diameter must be a positive finite number in mm."}
      (and (= :recess kind) (not (and border (>= border 0))))
      {:error "Recess border must be a non-negative finite number in mm."}
      :else {:cut (cond-> {:kind kind :depth depth}
                    (= :pit kind) (assoc :diameter diameter)
                    (= :recess kind) (assoc :border border))})))

(defn outline
  "Recover oriented boundary loops from wound source-space facet triangles.
  Internal edges cancel; open/non-manifold boundaries are rejected."
  [triangles]
  (let [edges (for [[a b c] triangles [p q] [[a b] [b c] [c a]]] [p q])
        grouped (group-by #(vec (sort %)) edges)
        boundary (mapv first (filter #(= 1 (count %)) (vals grouped)))
        nexts (into {} boundary)]
    (when (and (seq boundary) (= (count nexts) (count boundary)))
      (loop [remaining (set (keys nexts)) loops []]
        (if (empty? remaining)
          loops
          (let [start (first (sort remaining))
                ring (loop [p start seen #{} points []]
                       (cond
                         (and (= p start) (seq points)) points
                         (or (nil? p) (seen p) (not (remaining p))) nil
                         :else (recur (nexts p) (conj seen p) (conj points p))))]
            (when (and ring (>= (count ring) 3))
              (recur (reduce disj remaining ring) (conj loops ring)))))))))

(defn project [{:mount/keys [pos axis roll]} points]
  (let [up (math/cross axis roll)]
    (mapv (fn [p] (let [v (math/subtract p pos)] [(math/dot v roll) (math/dot v up)])) points)))

(defn point [{:mount/keys [pos axis roll]} [x y] z]
  (math/add pos (math/add (math/scale z axis)
                          (math/add (math/scale x roll) (math/scale y (math/cross axis roll))))))

(defn pit-rings
  "Center cuts in retained face bounds without changing the authored attachment frame.
  Mounts without an outline retain their legacy frame and capacity-section bounds."
  [mount]
  (let [bounds (split/face-bounds mount (mapcat identity (:mount/outline mount)))
        mount (if (split/valid-bounds? bounds)
                (let [[lower upper] bounds
                      center (mapv #(/ (+ %1 %2) 2.0) lower upper)]
                  (-> mount
                      (assoc :mount/pos (point mount center 0.0))
                      (assoc-in [:mount/split :bounds] (mapv #(mapv - % center) bounds))))
                mount)
        radius (/ (get-in mount [:mount/cut :diameter]) 2.0)
        ring (mapv (fn [i] (let [a (* 2.0 #?(:clj Math/PI :cljs js/Math.PI) (/ i circle-segments))]
                             [(* radius (#?(:clj Math/cos :cljs js/Math.cos) a))
                              (* radius (#?(:clj Math/sin :cljs js/Math.sin) a))]))
                   (range circle-segments))]
    (mapv (fn [frame] {:frame frame :rings [ring]}) (:frames (split/sections mount)))))

(defn wire-lines [frame rings depth]
  (vec (mapcat (fn [ring]
                 (let [top (mapv #(point frame % 0.0) ring)
                       bottom (mapv #(point frame % (- depth)) ring)
                       edges (fn [points] (mapv vector points (concat (rest points) [(first points)])))]
                   (concat (edges top) (edges bottom) (mapv vector top bottom)))) rings)))
