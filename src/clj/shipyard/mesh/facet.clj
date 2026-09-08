(ns shipyard.mesh.facet
  "Facet grouping and mount-frame derivation for M2 authoring.

  Input is the tier-0 `.symesh` decoded by `shipyard.wire/decode`: positions,
  indices, and counts in the exact triangle order the browser clicked."
  (:require [shipyard.math :as math]
            [shipyard.mesh.float :as mesh-float]))

(def default-options
  {:facet-angle-deg 1.0
   :facet-plane-epsilon-mm 0.01})

(def interface-match-options
  "The tolerance used to recover a legacy mount's selected face from its
  durable frame. This deliberately matches the former viewport matcher: it
  identifies triangles on the mount plane and then keeps only the connected
  component nearest the saved mount position."
  {:interface-plane-epsilon-mm 0.08
   :interface-normal-cos 0.999})

(def ^:private degenerate-area2-epsilon 1e-12)
(def ^:private component-epsilon 1e-9)

(defn- sq [x] (* x x))

(defn- canonicalize-sign [[x y z :as v]]
  (let [component (some #(when (> (Math/abs (double %)) component-epsilon) %) [x y z])]
    (if (and component (neg? component)) (math/scale -1.0 v) v)))

(defn- point-key [[x y z]]
  [(mesh-float/canonical-bits x)
   (mesh-float/canonical-bits y)
   (mesh-float/canonical-bits z)])

(defn- compare-point-key [[ax ay az] [bx by bz]]
  (let [c (compare ax bx)]
    (if (zero? c)
      (let [c (compare ay by)]
        (if (zero? c) (compare az bz) c))
      c)))

(defn- edge-key [a b]
  (let [ak (point-key a)
        bk (point-key b)]
    (if (pos? (compare-point-key ak bk)) [bk ak] [ak bk])))

(defn- index-count [{:keys [^ints indices index-count]}]
  (long (or index-count (alength indices))))

(defn- triangle-count [mesh]
  (let [n (index-count mesh)]
    (when-not (zero? (rem n 3))
      (throw (ex-info "mesh index count is not divisible by three"
                      {:code :invalid-mesh-cache :index-count n})))
    (quot n 3)))

(defn- vertex [{:keys [^floats positions]} i]
  (let [o (* 3 (long i))]
    [(double (aget positions o))
     (double (aget positions (+ o 1)))
     (double (aget positions (+ o 2)))]))

(defn- triangle-points [{:keys [^ints indices] :as mesh} triangle-index]
  (let [o (* 3 (long triangle-index))]
    [(vertex mesh (aget indices o))
     (vertex mesh (aget indices (+ o 1)))
     (vertex mesh (aget indices (+ o 2)))]))

(defn- triangle-geometry [mesh triangle-index]
  (let [[a b c :as points] (triangle-points mesh triangle-index)
        ab (math/subtract b a)
        ac (math/subtract c a)
        cr (math/cross ab ac)
        area2 (math/length cr)]
    (when (and (every? math/finite-number? (apply concat points))
               (> area2 degenerate-area2-epsilon))
      {:points points
       :cross cr
       :normal (math/scale (/ 1.0 area2) cr)
       :area2 area2})))

(defn- require-triangle [mesh triangle-index]
  (when-not (<= 0 triangle-index (dec (triangle-count mesh)))
    (throw (ex-info "triangle index is outside the mesh"
                    {:code :triangle-out-of-range
                     :triangle-index triangle-index
                     :triangle-count (triangle-count mesh)})))
  (or (triangle-geometry mesh triangle-index)
      (throw (ex-info "selected triangle is degenerate"
                      {:code :degenerate-facet
                       :triangle-index triangle-index}))))

(defn- triangle-edge-keys [mesh triangle-index]
  (let [[a b c] (triangle-points mesh triangle-index)]
    [(edge-key a b) (edge-key b c) (edge-key c a)]))

(defn- adjacency
  ([mesh] (adjacency mesh (range (triangle-count mesh))))
  ([mesh triangle-indices]
   (reduce
    (fn [adj tris]
      (if (= 2 (count tris))
        (let [[a b] tris]
          (-> adj
              (update a (fnil conj #{}) b)
              (update b (fnil conj #{}) a)))
        adj))
    {}
    (vals
     (reduce
      (fn [edges triangle-index]
        (reduce #(update %1 %2 (fnil conj []) triangle-index)
                edges
                (triangle-edge-keys mesh triangle-index)))
      {}
      triangle-indices)))))

(defn- point-on-plane? [{:keys [normal points]} epsilon p]
  (<= (Math/abs (double (math/dot normal (math/subtract p (first points))))) epsilon))

(defn- facet-neighbour? [start candidate cos-angle epsilon]
  (and (>= (math/dot (:normal candidate) (:normal start)) cos-angle)
       (every? #(point-on-plane? start epsilon %) (:points candidate))))

(defn- facet-indices [mesh triangle-index {:keys [facet-angle-deg facet-plane-epsilon-mm]}]
  (let [start (require-triangle mesh triangle-index)
        adj (adjacency mesh)
        cos-angle (Math/cos (Math/toRadians (double facet-angle-deg)))]
    (loop [queue (seq (get adj triangle-index))
           seen #{triangle-index}]
      (if-let [candidate-index (first queue)]
        (if (contains? seen candidate-index)
          (recur (next queue) seen)
          (let [candidate (triangle-geometry mesh candidate-index)]
            (if (and candidate
                     (facet-neighbour? start candidate cos-angle facet-plane-epsilon-mm))
              (recur (concat (next queue) (get adj candidate-index))
                     (conj seen candidate-index))
              (recur (next queue) seen))))
        (vec (sort seen))))))

(defn- triangle-center [points]
  (mapv (fn [component]
          (/ (reduce + (map #(nth % component) points)) 3.0))
        (range 3)))

(defn- distance-squared [a b]
  (math/dot (math/subtract a b) (math/subtract a b)))

(defn- mount-plane-triangle?
  [pos axis {:keys [normal points]} {:keys [interface-plane-epsilon-mm interface-normal-cos]}]
  (and (>= (Math/abs (double (math/dot normal axis))) interface-normal-cos)
       (every? #(<= (Math/abs (double (math/dot axis (math/subtract % pos))))
                    interface-plane-epsilon-mm)
               points)))

(defn match-frame
  "Return the connected tier-0 facet described by a durable mount frame.

  This is a server-only compatibility path for mounts authored before selected
  facet indices were retained. It reproduces the old viewport matching rule,
  then callers persist the result against the current mesh key so this work is
  performed at most once per legacy mount and mesh revision."
  ([mesh mount] (match-frame mesh mount nil))
  ([mesh {:mount/keys [pos axis]} opts]
   (when (and (vector? pos) (= 3 (count pos)))
     (when-let [axis (math/normalize axis)]
       (let [opts (merge interface-match-options opts)
             candidates (keep (fn [triangle-index]
                                (when-let [triangle (triangle-geometry mesh triangle-index)]
                                  (when (mount-plane-triangle? pos axis triangle opts)
                                    {:index triangle-index :points (:points triangle)})))
                              (range (triangle-count mesh)))]
         (when (seq candidates)
           (let [start (:index (first (sort-by #(distance-squared (triangle-center (:points %)) pos)
                                               candidates)))
                 candidate-indices (map :index candidates)
                 adjacent (adjacency mesh candidate-indices)]
             (loop [queue (list start)
                    seen #{}]
               (if-let [candidate-index (first queue)]
                 (if (contains? seen candidate-index)
                   (recur (rest queue) seen)
                   (recur (concat (rest queue) (get adjacent candidate-index))
                          (conj seen candidate-index)))
                 (vec (sort seen)))))))))))

(defn- unique-points [mesh indices]
  (vals
   (reduce
    (fn [points triangle-index]
      (reduce #(assoc %1 (point-key %2) %2)
              points
              (triangle-points mesh triangle-index)))
    (sorted-map)
    indices)))

(defn- bbox-midpoint [points]
  (let [mins (reduce (fn [[ax ay az] [x y z]]
                       [(min ax x) (min ay y) (min az z)])
                     [Double/POSITIVE_INFINITY
                      Double/POSITIVE_INFINITY
                      Double/POSITIVE_INFINITY]
                     points)
        maxs (reduce (fn [[ax ay az] [x y z]]
                       [(max ax x) (max ay y) (max az z)])
                     [Double/NEGATIVE_INFINITY
                      Double/NEGATIVE_INFINITY
                      Double/NEGATIVE_INFINITY]
                     points)]
    (mapv #(/ (+ %1 %2) 2.0) mins maxs)))

(defn- facet-axis [mesh indices]
  (let [axis (math/normalize
              (reduce
               (fn [acc triangle-index]
                 (math/add acc (:cross (require-triangle mesh triangle-index))))
               [0.0 0.0 0.0]
               indices))]
    (or axis
        (throw (ex-info "facet normal is degenerate"
                        {:code :degenerate-facet
                         :facet-indices indices})))))

(defn- fallback-roll [axis]
  (or (some (fn [world-axis]
              (some->> (math/project-onto-plane axis world-axis)
                       (math/normalize)
                       (canonicalize-sign)))
            [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])
      (throw (ex-info "could not derive a roll axis"
                      {:code :degenerate-facet}))))

(defn- plane-basis [axis]
  (let [u (fallback-roll axis)
        v (math/normalize (math/cross axis u))]
    [u v]))

(defn- projected-points [axis points]
  (let [[u v] (plane-basis axis)]
    (mapv (fn [p] {:point p :x (math/dot p u) :y (math/dot p v)}) points)))

(defn- cross2 [o a b]
  (- (* (- (:x a) (:x o)) (- (:y b) (:y o)))
     (* (- (:y a) (:y o)) (- (:x b) (:x o)))))

(defn- convex-hull [points]
  (let [sorted-points (vec (sort-by (juxt :x :y) points))
        step (fn [h p]
               (loop [h h]
                 (if (and (>= (count h) 2)
                          (not (pos? (cross2 (nth h (- (count h) 2)) (peek h) p))))
                   (recur (pop h))
                   (conj h p))))
        lower (reduce step [] sorted-points)
        upper (reduce step [] (rseq sorted-points))]
    (cond
      (<= (count sorted-points) 1) sorted-points
      :else (vec (concat (pop lower) (pop upper))))))

(defn- hull-edges [hull]
  (mapv (fn [a b]
          (let [delta (math/subtract (:point b) (:point a))
                len (math/length delta)]
            {:a a :b b :length len :direction (math/normalize delta)}))
        hull
        (concat (rest hull) [(first hull)])))

(defn- distinct-directions? [directions cos-angle]
  (let [dirs (vec directions)]
    (boolean
     (some (fn [[a b]] (< (Math/abs (double (math/dot a b))) cos-angle))
           (for [i (range (count dirs))
                 j (range (inc i) (count dirs))]
             [(dirs i) (dirs j)])))))

(defn- polygon-covariance-ambiguous? [hull]
  (let [pairs (map vector hull (concat (rest hull) [(first hull)]))
        moments (reduce
                 (fn [{:keys [area2 cx cy xx yy xy]} [a b]]
                   (let [x0 (:x a), y0 (:y a), x1 (:x b), y1 (:y b)
                         cr (- (* x0 y1) (* x1 y0))]
                     {:area2 (+ area2 cr)
                      :cx (+ cx (* (+ x0 x1) cr))
                      :cy (+ cy (* (+ y0 y1) cr))
                      :xx (+ xx (* (+ (sq x0) (* x0 x1) (sq x1)) cr))
                      :yy (+ yy (* (+ (sq y0) (* y0 y1) (sq y1)) cr))
                      :xy (+ xy (* (+ (* 2.0 x0 y0) (* x0 y1) (* x1 y0) (* 2.0 x1 y1)) cr))}))
                 {:area2 0.0 :cx 0.0 :cy 0.0 :xx 0.0 :yy 0.0 :xy 0.0}
                 pairs)
        area (/ (:area2 moments) 2.0)]
    (if (<= (Math/abs area) degenerate-area2-epsilon)
      true
      (let [centroid-x (/ (:cx moments) (* 6.0 area))
            centroid-y (/ (:cy moments) (* 6.0 area))
            cxx (- (/ (:xx moments) (* 12.0 area)) (sq centroid-x))
            cyy (- (/ (:yy moments) (* 12.0 area)) (sq centroid-y))
            cxy (- (/ (:xy moments) (* 24.0 area)) (* centroid-x centroid-y))
            delta (Math/sqrt (+ (sq (- cxx cyy)) (* 4.0 (sq cxy))))
            l1 (/ (+ cxx cyy delta) 2.0)
            l2 (/ (- (+ cxx cyy) delta) 2.0)
            m (max (Math/abs l1) (Math/abs l2))]
        (or (<= m component-epsilon)
            (<= (Math/abs (- l1 l2)) (* 0.01 m)))))))

(defn- world-axis-roll [axis]
  {:roll (fallback-roll axis)
   :roll-ambiguous? true
   :roll-source :world-axis})

(defn- roll-from-hull [axis points facet-angle-deg]
  (try
    (let [hull (convex-hull (projected-points axis points))
          edges (remove #(or (nil? (:direction %)) (<= (:length %) component-epsilon))
                        (hull-edges hull))
          max-length (reduce max 0.0 (map :length edges))
          longest (filter #(>= (:length %) (* 0.99 max-length)) edges)
          cos-angle (Math/cos (Math/toRadians (double facet-angle-deg)))
          ambiguous? (or (empty? longest)
                         (distinct-directions? (map :direction longest) cos-angle)
                         (polygon-covariance-ambiguous? hull))]
      (if ambiguous?
        (world-axis-roll axis)
        (if-let [roll (some->> (:direction (first longest))
                               (math/project-onto-plane axis)
                               (math/normalize)
                               (canonicalize-sign))]
          {:roll roll
           :roll-ambiguous? false
           :roll-source :hull-edge}
          (world-axis-roll axis))))
    (catch NullPointerException _
      (world-axis-roll axis))))

(defn- frame [mesh indices opts]
  (let [points (vec (unique-points mesh indices))
        axis (facet-axis mesh indices)
        {:keys [roll roll-ambiguous? roll-source]} (roll-from-hull axis points (:facet-angle-deg opts))]
    {:frame {:mount/pos (bbox-midpoint points)
             :mount/axis axis
             :mount/roll roll}
     :points points
     :roll-ambiguous? roll-ambiguous?
     :roll-source roll-source}))

(defn select
  "Return the connected facet and derived mount frame for `triangle-index`.

  Throws ex-info with `:code` for invalid or degenerate selections. Returned
  facet indices are transient; only the frame is durable."
  ([mesh triangle-index] (select mesh triangle-index nil))
  ([mesh triangle-index opts]
   (let [opts (merge default-options opts)
         indices (facet-indices mesh triangle-index opts)]
     (merge {:triangle-index triangle-index
             :facet-indices indices}
            (frame mesh indices opts)))))
