(ns shipyard.paint.emission
  "Immutable source-space surface moments. Geometry analysis belongs to workers."
  (:require [shipyard.math :as math]
            [shipyard.paint.faces :as faces]))

(defn triangle-moment
  "Area and weighted centroid/normal in the renderer's six dominant-axis buckets."
  [[a b c]]
  (let [cross (math/cross (math/subtract b a) (math/subtract c a))
        magnitude (math/length cross)
        area (* 0.5 magnitude)]
    (when (and (pos? area) (every? math/finite-number? (concat a b c)))
      (let [normal (math/scale (/ 1.0 magnitude) cross)
            [x y z] (mapv abs normal)
            axis (if (> x y) (if (> x z) 0 2) (if (> y z) 1 2))]
        {:bucket (+ (* 2 axis) (if (neg? (nth normal axis)) 0 1))
         :sum (into [area] (concat (math/scale (/ area 3) (math/add (math/add a b) c))
                                   (math/scale area normal)))}))))

(defn source-moments
  "One record per durable identity; duplicate geometric triangles accumulate energy."
  [{:keys [^floats positions ^ints indices index-count]}]
  (reduce
   (fn [result triangle]
     (let [points (mapv (fn [corner]
                          (let [offset (* 3 (aget indices (+ (* triangle 3) corner)))]
                            (mapv #(double (aget positions (+ offset %))) (range 3)))) (range 3))]
       (if-let [{:keys [bucket sum]} (triangle-moment points)]
         (update-in result [(faces/face-key points) bucket]
                    #(if % (mapv + % sum) sum))
         result)))
   {} (range (quot (or index-count (alength indices)) 3))))

(defn group-moments
  "Palette-independent summaries: a layer selector, or a complete custom material."
  [source mesh-key regions details]
  (let [regions (when (= mesh-key (:mesh-key regions)) (:faces regions))
        details (when (= mesh-key (:mesh-key details)) (:faces details))]
    (->> source
         (reduce-kv (fn [groups key buckets]
                      (let [selector (if-let [detail (get details key)]
                                       {:detail detail} {:layer (get regions key "Primary")})]
                        (reduce-kv (fn [groups bucket sum]
                                     (update-in groups [selector bucket] #(if % (mapv + % sum) sum)))
                                   groups buckets))) {})
         (mapv (fn [[selector buckets]] (assoc selector :buckets (into {} buckets)))))))
