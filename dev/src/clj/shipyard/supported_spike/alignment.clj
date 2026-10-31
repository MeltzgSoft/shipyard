(ns shipyard.supported-spike.alignment
  "Experimental rigid alignment using surviving scalene triangle correspondences.
  Candidate evidence only: not a production admission or publication decision."
  (:require [shipyard.geom :as geom]
            [shipyard.math :as math]))

(def defaults {:samples 2048 :signature-mm 0.01 :tolerance-mm 0.02 :candidates 24 :minimum-inliers 6})

(defn triangle [{:keys [^floats positions]} i]
  (mapv (fn [corner]
          (mapv #(double (aget positions (+ (* i 9) (* corner 3) %))) (range 3))) (range 3)))

(defn canonical-triangle
  "Order vertices by opposite edge length; skip degenerate/near-isosceles faces."
  [points quantum]
  (let [[a b c] points
        lengths [(math/length (math/subtract b c))
                 (math/length (math/subtract a c))
                 (math/length (math/subtract a b))]
        ordered (sort-by first (map vector lengths points))
        [u v w] (map first ordered)
        area (math/length (math/cross (math/subtract b a) (math/subtract c a)))]
    (when (and (> (- v u) (* 2 quantum)) (> (- w v) (* 2 quantum)) (> area 1.0e-5))
      {:key (mapv #(Math/round (/ (double %) quantum)) [u v w])
       :points (mapv second ordered) :area area})))

(defn- basis [[a b c]]
  (let [x (math/normalize (math/subtract b a))
        z (math/normalize (math/cross x (math/subtract c a)))]
    [x (math/cross z x) z]))

(defn rigid-transform
  "Source points -> target points, no scale or reflection."
  [source target]
  (let [from (basis source) to (basis target)
        rotate (fn [point]
                 (reduce math/add [0.0 0.0 0.0]
                         (map (fn [a b] (math/scale (math/dot a point) b)) from to)))
        translation (math/subtract (first target) (rotate (first source)))]
    (vec (concat (mapcat #(conj (rotate %) 0.0) [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])
                 translation [1.0]))))

(defn- apply-point [matrix [x y z]]
  ;; Matrices here are generated internally, not admitted as external domain data.
  (mapv (fn [row] (+ (* (nth matrix row) x) (* (nth matrix (+ row 4)) y)
                     (* (nth matrix (+ row 8)) z) (nth matrix (+ row 12)))) (range 3)))

(defn- pair-error [matrix [source target]]
  (reduce max (map #(math/length (math/subtract (apply-point matrix %1) %2)) source target)))

(defn- bucket [index item]
  (if item
    ;; Only unique signatures will be used; retain at most two faces per bucket.
    (update index (:key item) #(vec (take 2 (conj (or % []) item)))) index))

(defn correspondences [source target {:keys [samples signature-mm]}]
  (let [n (:triangle-count source) k (min n samples)
        source-index (reduce (fn [index i]
                               (bucket index (canonical-triangle (triangle source (quot (* i n) k)) signature-mm)))
                             {} (range k))
        wanted (set (keys source-index))
        target-index (reduce (fn [index i]
                               (let [item (canonical-triangle (triangle target i) signature-mm)]
                                 (if (wanted (:key item)) (bucket index item) index)))
                             {} (range (:triangle-count target)))]
    (->> source-index
         (keep (fn [[key entries]]
                 (let [matches (get target-index key)]
                   (when (and (= 1 (count entries)) (= 1 (count matches)))
                     {:source (:points (first entries)) :target (:points (first matches)) :area (:area (first entries))}))))
         (sort-by :area >)
         (vec))))

(defn align
  ([source target] (align source target defaults))
  ([source target options]
   (let [{:keys [candidates tolerance-mm minimum-inliers] :as options} (merge defaults options)
         matches (correspondences source target options)
         pairs (mapv (juxt :source :target) matches)
         evidence {:matches (count matches) :source-triangles (:triangle-count source)
                   :supported-triangles (:triangle-count target)}]
     (if (< (count matches) minimum-inliers)
       (assoc evidence :status :insufficient-evidence)
       (let [scored (mapv (fn [[source target]]
                            (let [matrix (rigid-transform source target)
                                  errors (mapv #(pair-error matrix %) pairs)
                                  inliers (filter #(<= % tolerance-mm) errors)]
                              {:matrix matrix :inliers (count inliers)
                               :max-inlier-error-mm (reduce max 0.0 inliers)}))
                          (take candidates pairs))
             best (first (sort-by (juxt (comp - :inliers) :max-inlier-error-mm) scored))
             ratio (/ (:inliers best) (double (count pairs)))
             anchors (mapcat :source (take 12 matches))
             distinct? (fn [candidate]
                         (some #(> (math/length (math/subtract (apply-point (:matrix best) %)
                                                               (apply-point (:matrix candidate) %))) (* 4 tolerance-mm)) anchors))
             ambiguous? (some #(and (>= (:inliers %) (* 0.95 (:inliers best))) (distinct? %)) scored)]
         (merge evidence best {:inlier-ratio ratio
                               :status (cond (< (:inliers best) minimum-inliers) :no-consensus
                                             (< ratio 0.8) :no-consensus
                                             ambiguous? :ambiguous
                                             :else :candidate)}))))))

(defn transfer-mount
  "Transform transient physical geometry only; never transfer facet indices or authoring identity."
  [matrix mount]
  (let [direction (fn [v] (math/subtract (geom/transform-point matrix v)
                                         (geom/transform-point matrix [0.0 0.0 0.0])))
        result (-> (select-keys mount [:mount/id :mount/kind :mount/capacity :mount/split :mount/cut])
                   (assoc :mount/pos (geom/transform-point matrix (:mount/pos mount))
                          :mount/axis (direction (:mount/axis mount))
                          :mount/roll (direction (:mount/roll mount))))]
    (cond-> result
      (:mount/cut result) (update :mount/cut dissoc :mesh-key)
      (:mount/outline mount) (assoc :mount/outline (mapv #(mapv (partial geom/transform-point matrix) %) (:mount/outline mount))))))
