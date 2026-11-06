(ns shipyard.mesh.facet
  "Facet grouping and mount-frame derivation for M2 authoring.

  Input is the tier-0 `.symesh` decoded by `shipyard.wire/decode`: positions,
  indices, and counts in the exact triangle order the browser clicked."
  (:require [shipyard.math :as math]
            [shipyard.regions.surfaces :as surfaces]
            [shipyard.settings.transforms :as settings]
            [shipyard.mesh.edges :as mesh-edges]
            [shipyard.triangle :as triangle]
            [shipyard.mesh.float :as mesh-float]))

(def default-options
  {:facet-angle-deg 1.0
   :facet-plane-epsilon-mm 0.01
   :seam-tolerance-mm settings/seam-default})

(def interface-match-options
  "The tolerance used to recover a legacy mount's selected face from its
  durable frame. This deliberately matches the former viewport matcher: it
  identifies triangles on the mount plane and then keeps only the connected
  component nearest the saved mount position."
  {:interface-plane-epsilon-mm 0.08
   :interface-normal-cos 0.999})

(def ^:private degenerate-area2-epsilon 1e-12)
(def ^:private component-epsilon 1e-9)

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

(defn- edge-triangles [mesh triangle-indices]
  (reduce
   (fn [edges triangle-index]
     (reduce #(update %1 %2 (fnil conj []) triangle-index)
             edges
             (triangle-edge-keys mesh triangle-index)))
   {}
   triangle-indices))

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
    (vals (edge-triangles mesh triangle-indices)))))

(defn- point-on-plane? [{:keys [normal points]} epsilon p]
  (<= (Math/abs (double (math/dot normal (math/subtract p (first points))))) epsilon))

(defn- facet-neighbour? [start candidate cos-angle epsilon]
  (and (>= (+ (math/dot (:normal candidate) (:normal start)) 1.0e-12) cos-angle)
       (or (nil? epsilon) (every? #(point-on-plane? start epsilon %) (:points candidate)))))

(defn- edge-adjacency [edges]
  (reduce (fn [result tris]
            (let [owners (set tris)]
              (reduce (fn [result i] (update result i (fnil into #{}) (disj owners i))) result owners)))
          {} (vals edges)))

(defn- connected [adj seeds]
  (loop [pending (vec seeds) selected #{}]
    (if-let [i (peek pending)]
      (if (contains? selected i)
        (recur (pop pending) selected)
        (recur (into (pop pending) (get adj i)) (conj selected i)))
      selected)))

(defn- seam-adjacency [mesh candidates tolerance]
  (let [directed (vec (for [i (sort candidates)
                            :let [[a b c] (triangle-points mesh i)]
                            edge [[a b] [b c] [c a]]]
                        [i edge]))
        edges (reduce (fn [result [[i _] segments]]
                        (reduce (fn [result [a b]] (update result (edge-key a b) (fnil conj #{}) i))
                                result segments))
                      {} (map vector directed (mesh-edges/split-edges (mapv second directed) tolerance)))]
    (edge-adjacency edges)))

(defn- selection-bounds [mesh indices]
  (reduce (fn [[lo hi] point] [(mapv min lo point) (mapv max hi point)])
          [(vec (repeat 3 Double/POSITIVE_INFINITY)) (vec (repeat 3 Double/NEGATIVE_INFINITY))]
          (mapcat #(triangle-points mesh %) indices)))

(defn- near-bounds? [mesh i [lo hi] tolerance]
  (let [points (triangle-points mesh i)]
    (every? (fn [axis]
              (and (<= (reduce min (map #(nth % axis) points)) (+ (nth hi axis) tolerance))
                   (>= (reduce max (map #(nth % axis) points)) (- (nth lo axis) tolerance))))
            (range 3))))

(defn- angular-indices [mesh triangle-index angle tolerance]
  (let [normals (into {} (keep (fn [i]
                                 (when-let [geometry (triangle-geometry mesh i)]
                                   [i (:normal geometry)])))
                      (range (triangle-count mesh)))
        edges (edge-triangles mesh (keys normals))
        adjacent (surfaces/angle-adjacency normals (edge-adjacency edges) angle)]
    (loop [selected (connected adjacent [triangle-index])]
      (let [boundary (into #{} (filter (fn [i]
                                         (some #(= 1 (count (filter selected (get edges %))))
                                               (triangle-edge-keys mesh i)))) selected)]
        (if (or (empty? boundary) (= (count selected) (count normals)))
          (vec (sort selected))
          (let [bounds (selection-bounds mesh boundary)
                nearby (into boundary (filter #(and (not (contains? selected %))
                                                    (near-bounds? mesh % bounds (max tolerance 1e-6)))) (keys normals))
                ;; Exact connectivity already covers the interior. Only repair
                ;; boundary triangles and nearby candidates, not the whole mesh.
                local (surfaces/angle-adjacency normals (seam-adjacency mesh nearby tolerance) angle)
                joined (connected local boundary)
                grown (connected adjacent (into selected joined))]
            (if (= selected grown)
              (vec (sort selected))
              (recur grown))))))))

(defn- facet-indices [mesh triangle-index {:keys [facet-angle-deg facet-plane-epsilon-mm selection-angle-deg seam-tolerance-mm selection-rule]}]
  (let [start (require-triangle mesh triangle-index)]
    (if (and (some? selection-angle-deg) (not= :seed selection-rule))
      (angular-indices mesh triangle-index selection-angle-deg seam-tolerance-mm)
      ;; Preserve the planar API and older saved seed-bound selections.
      (let [cos-angle (Math/cos (Math/toRadians (double (or selection-angle-deg facet-angle-deg))))
            candidates (into #{} (filter (fn [i]
                                           (when-let [candidate (triangle-geometry mesh i)]
                                             (facet-neighbour? start candidate cos-angle
                                                               (when-not (some? selection-angle-deg) facet-plane-epsilon-mm)))))
                             (range (triangle-count mesh)))]
        (vec (sort (connected (seam-adjacency mesh candidates seam-tolerance-mm) [triangle-index])))))))

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
  facet indices were retained. It chooses the nearest triangle surface, then grows the connected coplanar face;
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
           (let [start (:index (first (sort-by #(distance-squared (apply triangle/closest-point-on-triangle pos (:points %)) pos)
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

(defn- facet-axis [mesh indices fallback]
  (let [[sum area] (reduce (fn [[sum area] triangle-index]
                             (let [{:keys [cross area2]} (require-triangle mesh triangle-index)]
                               [(math/add sum cross) (+ area area2)]))
                           [[0.0 0.0 0.0] 0.0] indices)
        axis (math/normalize sum (max degenerate-area2-epsilon (* area 1e-10)))]
    (or axis fallback
        (throw (ex-info "facet normal is degenerate"
                        {:code :degenerate-facet :facet-indices indices})))))

(defn- fallback-roll [axis]
  (or (some (fn [world-axis]
              (some->> (math/project-onto-plane axis world-axis)
                       (math/normalize)
                       (canonicalize-sign)))
            [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])
      (throw (ex-info "could not derive a roll axis"
                      {:code :degenerate-facet}))))

(defn- boundary-edges [triangles seam-tolerance-mm]
  (mapcat
   (fn [ring]
     ;; Ignore triangulation vertices on a straight boundary: they do not
     ;; shorten the face edge or influence which edge supplies the default.
     (let [corners (vec (keep-indexed
                         (fn [i point]
                           (let [before (nth ring (mod (dec i) (count ring)))
                                 after (nth ring (mod (inc i) (count ring)))
                                 incoming (math/normalize (math/subtract point before))
                                 outgoing (math/normalize (math/subtract after point))]
                             (when (or (nil? incoming) (nil? outgoing)
                                       (< (math/dot incoming outgoing) (- 1.0 1e-8)))
                               point)))
                         ring))]
       (map (fn [a b]
              (let [delta (math/subtract b a)]
                {:length (math/length delta) :direction (math/normalize delta)}))
            corners (concat (rest corners) [(first corners)]))))
   (mesh-edges/outline triangles seam-tolerance-mm)))

(defn- distinct-directions? [directions cos-angle]
  (let [dirs (vec directions)]
    (boolean
     (some (fn [[a b]] (< (Math/abs (double (math/dot a b))) cos-angle))
           (for [i (range (count dirs))
                 j (range (inc i) (count dirs))]
             [(dirs i) (dirs j)])))))

(defn- world-axis-roll [axis]
  {:roll (fallback-roll axis)
   :roll-ambiguous? true
   :roll-source :world-axis})

(defn- roll-from-boundary [axis triangles facet-angle-deg seam-tolerance-mm]
  (let [edges (remove #(or (nil? (:direction %)) (<= (:length %) component-epsilon))
                      (boundary-edges triangles seam-tolerance-mm))
        max-length (reduce max 0.0 (map :length edges))
        longest (filter #(>= (:length %) (* 0.99 max-length)) edges)
        cos-angle (Math/cos (Math/toRadians (double facet-angle-deg)))
        ambiguous? (or (empty? longest)
                       (distinct-directions? (map :direction longest) cos-angle))]
    ;; Equal edges still supply a boundary direction, never a world-axis
    ;; replacement. Boundary loops have a deterministic starting vertex.
    (if-let [roll (some->> (:direction (first (sort-by :length > longest)))
                           (math/cross axis)
                           (math/normalize)
                           (canonicalize-sign))]
      {:roll roll :roll-ambiguous? (boolean ambiguous?) :roll-source :boundary-edge-normal}
      (world-axis-roll axis))))

(defn- frame [mesh indices triangle-index opts]
  (let [points (vec (unique-points mesh indices))
        axis (facet-axis mesh indices (when (and (some? (:selection-angle-deg opts))
                                                 (not= :seed (:selection-rule opts)))
                                        (:normal (require-triangle mesh triangle-index))))
        {:keys [roll roll-ambiguous? roll-source]} (roll-from-boundary axis (mapv #(triangle-points mesh %) indices) (:facet-angle-deg opts) (:seam-tolerance-mm opts))]
    {:frame {:mount/pos (bbox-midpoint points)
             :mount/axis axis
             :mount/roll roll}
     :points points
     :roll-ambiguous? roll-ambiguous?
     :roll-source roll-source}))

(defn- surrounding-heights
  "Return each non-coplanar boundary neighbour's signed height from the facet.

  The facet normal points toward the side on which a mount is authored.  A
  neighbouring surface above that plane therefore forms the wall of a recess;
  one below it forms an outward projection."
  [mesh indices frame {:keys [facet-plane-epsilon-mm]}]
  (let [selected (set indices)
        adjacent (adjacency mesh)
        origin (:mount/pos frame)
        axis (:mount/axis frame)]
    (->> indices
         (mapcat #(get adjacent %))
         (remove selected)
         distinct
         (mapcat #(triangle-points mesh %))
         (map #(math/dot axis (math/subtract % origin)))
         (filter #(> (Math/abs (double %)) facet-plane-epsilon-mm)))))

(defn- kind-hint [mesh indices frame opts]
  (let [heights (surrounding-heights mesh indices frame opts)]
    ;; A plug has no inward wall at all. One positive neighbour is enough to
    ;; suggest a recess; mixed boundaries occur on real hardpoints where an
    ;; otherwise valid socket joins an outer hull surface.
    (if (some pos? heights) :socket :plug)))

(defn select
  "Return the connected facet and derived mount frame for `triangle-index`.

  Throws ex-info with `:code` for invalid or degenerate selections. Returned
  facet indices are transient; only the frame is durable."
  ([mesh triangle-index] (select mesh triangle-index nil))
  ([mesh triangle-index opts]
   (let [opts (merge default-options opts)
         _ (when (and (some? (:selection-angle-deg opts))
                      (not (and (math/finite-number? (:selection-angle-deg opts))
                                (<= 0 (:selection-angle-deg opts) 90))))
             (throw (ex-info "Face angle tolerance must be between 0 and 90 degrees." {:code :invalid-angle})))
         _ (when-not (and (math/finite-number? (:seam-tolerance-mm opts))
                          (<= 0 (:seam-tolerance-mm opts) 0.1))
             (throw (ex-info "Mount face seam tolerance must be between 0 and 0.1 mm." {:code :invalid-seam-tolerance})))
         indices (facet-indices mesh triangle-index opts)
         frame-data (frame mesh indices triangle-index opts)]
     (merge {:triangle-index triangle-index
             :facet-indices indices
             :kind-hint (kind-hint mesh indices (:frame frame-data) opts)}
            frame-data))))
