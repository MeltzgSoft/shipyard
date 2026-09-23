(ns shipyard.regions.surfaces
  "Connected planar brush surfaces, keyed by stable tier-0 triangle order."
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

(defn- surface [triangles normals adj assigned seed]
  (let [normal (get normals seed) origin (first (get triangles seed))
        flat? (fn [i]
                (and normal (get normals i)
                     ;; Match mount picking's 1 degree / 0.01 mm tolerances,
                     ;; always relative to the seed plane, never a moving plane.
                     (>= (math/dot normal (get normals i)) 0.9998476951563913)
                     (every? #(<= (abs (math/dot normal (math/subtract % origin))) 0.01)
                             (get triangles i))))]
    (loop [pending (vec (get adj seed)) visited #{seed} selected #{seed}]
      (if-let [i (peek pending)]
        (let [rest (pop pending)]
          (if (contains? visited i)
            (recur rest visited selected)
            (if (and (nil? (get assigned i)) (flat? i))
              (recur (into rest (get adj i)) (conj visited i) (conj selected i))
              (recur rest (conj visited i) selected))))
        selected))))

(defn groups
  "Partition triangles into connected flat surfaces. Shared sets keep storage linear."
  [triangles]
  (let [adj (adjacency triangles)
        normals (mapv (fn [[a b c]] (math/normalize (math/cross (math/subtract b a) (math/subtract c a)) 1e-12)) triangles)]
    (reduce (fn [assigned i]
              (if (get assigned i) assigned
                  (let [members (surface triangles normals adj assigned i)]
                    (reduce #(assoc %1 %2 members) assigned members))))
            (vec (repeat (count triangles) nil)) (range (count triangles)))))

(defn expand [groups seeds]
  (into #{} (mapcat #(get groups % #{%})) seeds))
