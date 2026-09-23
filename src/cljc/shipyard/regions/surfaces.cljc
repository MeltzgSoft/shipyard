(ns shipyard.regions.surfaces
  "Connected brush surfaces with an adjacent-triangle angle tolerance, keyed by stable tier-0 triangle order."
  (:require [shipyard.math :as math]))

(defn- adjacency [triangles]
  (let [edges (reduce-kv
               (fn [edges i [a b c]]
                 (reduce #(update %1 (vec (sort %2)) (fnil conj []) i)
                         edges [[a b] [b c] [c a]])) {} triangles)]
    (reduce (fn [adj owners]
              ;; Do not cross non-manifold seams.
              (if (= 2 (count owners))
                (let [[a b] owners]
                  (-> adj (update a (fnil conj #{}) b) (update b (fnil conj #{}) a)))
                adj)) {} (vals edges))))

(defn- surface [adj seed]
  (loop [pending [seed] selected #{}]
    (if-let [i (peek pending)]
      (if (contains? selected i)
        (recur (pop pending) selected)
        (recur (into (pop pending) (get adj i)) (conj selected i)))
      selected)))

(defn groups
  "Partition by the angle between adjacent triangles, allowing gradual curves.
  Angles are degrees; shared sets keep storage linear. Non-manifold seams stop growth."
  ([triangles] (groups triangles 1))
  ([triangles angle]
   (let [normals (mapv (fn [[a b c]] (math/normalize (math/cross (math/subtract b a) (math/subtract c a)) 1e-12)) triangles)
         threshold (#?(:clj Math/cos :cljs js/Math.cos) (* angle (/ #?(:clj Math/PI :cljs js/Math.PI) 180)))
         adj (into {} (map (fn [[i neighbors]]
                             [i (filterv #(and (get normals i) (get normals %)
                                               (>= (math/dot (get normals i) (get normals %)) (- threshold 1e-10))) neighbors)]))
                   (adjacency triangles))]
     (reduce (fn [assigned i]
               (if (get assigned i) assigned
                   (let [members (surface adj i)]
                     (reduce #(assoc %1 %2 members) assigned members))))
             (vec (repeat (count triangles) nil)) (range (count triangles))))))

(defn expand [groups seeds]
  (into #{} (mapcat #(get groups % #{%})) seeds))
