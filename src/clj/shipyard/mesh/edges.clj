(ns shipyard.mesh.edges
  "Split geometric edges at existing vertices without changing source triangles."
  (:require [shipyard.math :as math]
            [shipyard.mount.cut :as cut])
  (:import [org.locationtech.jts.geom Envelope]
           [org.locationtech.jts.index.strtree STRtree]))

(def ^:private line-epsilon-mm 1e-6)

(defn- projection-axes [points]
  (->> (range 3)
       (sort-by (fn [axis]
                  (- (reduce max (map #(nth % axis) points))
                     (reduce min (map #(nth % axis) points)))) >)
       (take 2)
       (vec)))

(defn- envelope ^Envelope [[u v] a b]
  (doto (Envelope. (double (nth a u)) (double (nth b u))
                   (double (nth a v)) (double (nth b v)))
    (.expandBy line-epsilon-mm)))

(defn split-edges
  "Return directed subedges per input edge, split at existing interior vertices.
  A spatial index limits candidates; the final test is in all three dimensions.
  Only points within 1e-6 mm of the segment line are admitted. Endpoints are
  never welded, and point contact alone creates no shared segment."
  [edges]
  (if (empty? edges)
    []
    (let [points (vec (distinct (mapcat identity edges)))
          axes (projection-axes points)
          tree (STRtree.)]
      (doseq [point points] (.insert tree (envelope axes point point) point))
      (.build tree)
      (mapv (fn [[a b]]
              (let [delta (math/subtract b a)
                    length2 (math/dot delta delta)
                    interior (when (pos? length2)
                               (keep (fn [point]
                                       (let [offset (math/subtract point a)
                                             t (/ (math/dot offset delta) length2)
                                             residual (math/subtract offset (math/scale t delta))]
                                         (when (and (< 0.0 t 1.0)
                                                    (<= (math/dot residual residual)
                                                        (* line-epsilon-mm line-epsilon-mm)))
                                           [t point])))
                                     (.query tree (envelope axes a b))))
                    path (into [a] (concat (map second (sort-by first interior)) [b]))]
                (mapv vector path (rest path))))
            edges))))

(defn outline
  "Recover a facet outline after cancelling internal edges, including split seams."
  [triangles]
  (cut/outline-edges
   (mapcat identity
           (split-edges (vec (for [[a b c] triangles [p q] [[a b] [b c] [c a]]] [p q]))))))
