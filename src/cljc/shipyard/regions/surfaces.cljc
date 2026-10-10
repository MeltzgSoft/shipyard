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

(defn topology
  "Prepare immutable coordinate-shared adjacency and normals once per source."
  [triangles]
  {:normals (mapv (fn [[a b c]] (math/normalize (math/cross (math/subtract b a) (math/subtract c a)) 1e-12)) triangles)
   :adjacency (adjacency triangles)})

(defn partition-components
  "Compact linear projection: one component ID per triangle, offsets and members.
  Partition an already prepared topology without rebuilding its edge graph."
  [{:keys [normals adjacency]} angle]
  (let [threshold (#?(:clj Math/cos :cljs js/Math.cos) (* angle (/ #?(:clj Math/PI :cljs js/Math.PI) 180)))
        adj (into {} (map (fn [[i neighbors]]
                            [i (filterv #(and (get normals i) (get normals %)
                                              (>= (math/dot (get normals i) (get normals %)) (- threshold 1e-10))) neighbors)])) adjacency)]
    (loop [i 0 ids (vec (repeat (count normals) nil)) offsets [0] members []]
      (cond
        (= i (count normals)) {:ids ids :offsets offsets :members members}
        (some? (get ids i)) (recur (inc i) ids offsets members)
        :else (let [component (dec (count offsets)) selected (vec (sort (surface adj i)))]
                (recur (inc i) (reduce #(assoc %1 %2 component) ids selected)
                       (conj offsets (+ (count members) (count selected))) (into members selected)))))))

(defn expand-components
  "Live brush lookup touches only the selected components, never a mesh graph."
  [{:keys [ids offsets members]} seeds]
  (reduce (fn [selected component]
            (into selected (subvec members (get offsets component) (get offsets (inc component)))))
          #{} (into #{} (keep #(get ids %)) seeds)))

(defn groups
  "Compatibility projection for callers/tests; browser brushing uses compact components."
  ([triangles] (groups triangles 1))
  ([triangles angle]
   (let [{:keys [ids offsets members]} (partition-components (topology triangles) angle)
         components (mapv #(set (subvec members %1 %2)) offsets (rest offsets))]
     (mapv #(get components %) ids))))

(defn expand
  "Expand each connected component only once, even when many seeds hit it."
  [groups seeds]
  (reduce (fn [selected seed]
            (if (contains? selected seed) selected
                (into selected (get groups seed #{seed}))))
          #{} seeds))
