(ns shipyard.mesh.facet-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.math :as math]
            [shipyard.mount-angle-fixture :as curved]
            [shipyard.fixtures :as fixtures]
            [shipyard.geom :as geom]
            [shipyard.mesh.edges :as mesh-edges]
            [shipyard.mount-seam-fixture :as seam]
            [shipyard.mesh.facet :as facet]))

(defn- mesh
  "Build an indexed mesh with one fresh vertex id per triangle corner.

  That simulates the authoring shape M2 cares about: geometric neighbours may
  share exact positions while using distinct crease-split vertex ids."
  [triangles]
  (let [positions (float-array (mapcat identity (apply concat triangles)))
        indices (int-array (range (* 3 (count triangles))))]
    {:positions positions
     :indices indices
     :vertex-count (* 3 (count triangles))
     :index-count (alength indices)}))

(defn- close? [a b]
  (< (Math/abs (- (double a) (double b))) 1e-9))

(defn- vec-close? [expected actual]
  (every? true? (map close? expected actual)))

(defn- finite-vec? [v]
  (every? #(Double/isFinite (double %)) v))

(defn- code-of [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:code (ex-data e)))))

(def ^:private contract-mesh
  (mesh
   [[[0 0 0] [4 0 0] [0 2 0]]
    [[4 0 0] [4 2 0] [0 2 0]]

    ;; Shares the horizontal rectangle's y=2 edge geometrically, but is a hard edge.
    [[0 2 0] [0 2 1] [4 2 0]]
    [[4 2 0] [0 2 1] [4 2 1]]

    ;; Coplanar with the first rectangle, but disconnected.
    [[6 0 0] [8 0 0] [6 1 0]]
    [[8 0 0] [8 1 0] [6 1 0]]

    ;; Equal non-parallel hull edges make roll ambiguous.
    [[0 0 3] [2 0 3] [0 2 3]]
    [[2 0 3] [2 2 3] [0 2 3]]]))

(defn- recessed-mesh [height]
  (mesh
   [[[0 0 0] [2 0 0] [0 2 0]]
    [[2 0 0] [2 2 0] [0 2 0]]
    [[0 0 0] [2 0 0] [1 -1 height]]
    [[2 0 0] [2 2 0] [3 1 height]]
    [[2 2 0] [0 2 0] [1 3 height]]
    [[0 2 0] [0 0 0] [-1 1 height]]]))

(def ^:private mixed-recess-mesh
  (mesh
   [[[0 0 0] [2 0 0] [0 2 0]]
    [[2 0 0] [2 2 0] [0 2 0]]
    [[0 0 0] [2 0 0] [1 -1 1]]
    [[2 0 0] [2 2 0] [3 1 -1]]
    [[2 2 0] [0 2 0] [1 3 -1]]
    [[0 2 0] [0 0 0] [-1 1 -1]]]))

(deftest select-test
  (testing "groups the connected coplanar facet across geometric, not vertex-id, edges"
    (let [{:keys [facet-indices frame roll-ambiguous? roll-source]} (facet/select contract-mesh 0)]
      (is (= [0 1] facet-indices))
      (is (vec-close? [2.0 1.0 0.0] (:mount/pos frame)))
      (is (vec-close? [0.0 0.0 1.0] (:mount/axis frame)))
      (is (vec-close? [0.0 1.0 0.0] (:mount/roll frame)))
      (is (finite-vec? (:mount/pos frame)))
      (is (close? 1.0 (math/length (:mount/axis frame))))
      (is (close? 1.0 (math/length (:mount/roll frame))))
      (is (close? 0.0 (math/dot (:mount/axis frame) (:mount/roll frame))))
      (is (false? roll-ambiguous?))
      (is (= :boundary-edge-normal roll-source))))

  (testing "a hard edge is adjacent but not part of the facet"
    (is (= [2 3] (:facet-indices (facet/select contract-mesh 2)))))

  (testing "disconnected coplanar triangles stay out"
    (is (= [4 5] (:facet-indices (facet/select contract-mesh 4)))))

  (testing "the same facet has the same durable frame regardless of which triangle is picked"
    (is (= (:frame (facet/select contract-mesh 0))
           (:frame (facet/select contract-mesh 1)))))

  (testing "squares choose an actual boundary edge deterministically and report the tie"
    (let [{:keys [facet-indices frame roll-ambiguous? roll-source]} (facet/select contract-mesh 6)]
      (is (= [6 7] facet-indices))
      (is (vec-close? [1.0 1.0 3.0] (:mount/pos frame)))
      (is (vec-close? [0.0 0.0 1.0] (:mount/axis frame)))
      (is (vec-close? [0.0 1.0 0.0] (:mount/roll frame)))
      (is (true? roll-ambiguous?))
      (is (= :boundary-edge-normal roll-source)))))

(deftest match-frame-test
  (testing "recovers the connected face nearest a legacy mount frame"
    (is (= [0 1]
           (facet/match-frame contract-mesh
                              {:mount/pos [2.0 1.0 0.0]
                               :mount/axis [0.0 0.0 1.0]}))))
  (testing "accepts the reverse axis, as the former viewport matcher did"
    (is (= [0 1]
           (facet/match-frame contract-mesh
                              {:mount/pos [2.0 1.0 0.0]
                               :mount/axis [0.0 0.0 -1.0]}))))
  (testing "returns nil when no triangle is on the durable mount plane"
    (is (nil? (facet/match-frame contract-mesh
                                 {:mount/pos [2.0 1.0 9.0]
                                  :mount/axis [0.0 0.0 1.0]})))))

(deftest roll-fallback-test
  (testing "an unavailable boundary still yields a selectable fallback frame"
    (let [{:keys [frame roll-ambiguous? roll-source]}
          (with-redefs-fn {#'mesh-edges/outline (constantly nil)}
            #(facet/select contract-mesh 0))]
      (is (vec-close? [2.0 1.0 0.0] (:mount/pos frame)))
      (is (vec-close? [0.0 0.0 1.0] (:mount/axis frame)))
      (is (vec-close? [1.0 0.0 0.0] (:mount/roll frame)))
      (is (true? roll-ambiguous?))
      (is (= :world-axis roll-source)))))

(deftest invalid-selection-test
  (testing "negative triangle indices are rejected"
    (is (= :triangle-out-of-range (code-of #(facet/select contract-mesh -1)))))

  (testing "indices past the mesh are rejected"
    (is (= :triangle-out-of-range (code-of #(facet/select contract-mesh 8)))))

  (testing "an index count that is not triangles is an invalid mesh cache"
    (is (= :invalid-mesh-cache
           (code-of #(facet/select (assoc contract-mesh :index-count 23) 0))))))

(deftest degenerate-facet-test
  (testing "a degenerate selected triangle is rejected"
    (is (= :degenerate-facet
           (code-of #(facet/select (mesh [[[0 0 0] [1 0 0] [1 0 0]]]) 0)))))

  (testing "a non-finite selected triangle is rejected"
    (is (= :degenerate-facet
           (code-of #(facet/select (mesh [[[0 0 0] [1 0 0] [##Inf 1 0]]]) 0)))))

  (testing "degenerate neighbours are not crossed"
    (let [m (mesh [[[0 0 0] [2 0 0] [0 2 0]]
                   [[2 0 0] [0 2 0] [0 2 0]]])]
      (is (= [0] (:facet-indices (facet/select m 0)))))))

(deftest reversed-neighbour-test
  (testing "a reversed coplanar neighbour is not crossed"
    (let [m (mesh [[[0 0 0] [2 0 0] [0 2 0]]
                   [[2 0 0] [0 2 0] [2 2 0]]])]
      (is (= [0] (:facet-indices (facet/select m 0)))))))

(deftest kind-hint-test
  (testing "any recess-forming side defaults to an editable socket"
    (is (= :socket (:kind-hint (facet/select (recessed-mesh 1) 0)))))
  (testing "a hardpoint may also meet an outer surface"
    (is (= :socket (:kind-hint (facet/select mixed-recess-mesh 0)))))

  (testing "projections and open faces default to plug"
    (is (= :plug (:kind-hint (facet/select (recessed-mesh -1) 0))))
    (let [flat (mesh [[[0 0 0] [2 0 0] [0 2 0]]])]
      (is (= :plug (:kind-hint (facet/select flat 0)))))))

(deftest non-manifold-edge-test
  (testing "a non-coplanar third triangle does not block the two coplanar neighbours"
    (let [m (mesh [[[0 0 0] [2 0 0] [0 1 0]]
                   [[2 0 0] [0 0 0] [2 -1 0]]
                   [[0 0 0] [2 0 0] [1 0 1]]])]
      (is (= [0 1] (:facet-indices (facet/select m 0))))
      (is (= [2] (:facet-indices (facet/select m 2))))))
  (testing "opposing internal caps do not divide a same-facing mounting surface"
    (let [m (mesh seam/triangles)
          left (facet/select m 2) right (facet/select m 6)]
      (is (= (vec (range 8)) (:facet-indices left) (:facet-indices right)))
      (is (= (:frame left) (:frame right)))
      (is (vec-close? [0 0 0] (get-in left [:frame :mount/pos])))
      (is (vec-close? [0 1 0] (get-in left [:frame :mount/axis])))
      (is (= :boundary-edge-normal (:roll-source left)))
      (is (= [10 11] (:facet-indices (facet/select m 10))))))
  (testing "all matching coplanar neighbours are included even with duplicated triangles"
    (let [a [[0 0 0] [2 0 0] [0 1 0]]
          b [[2 0 0] [0 0 0] [2 -1 0]]
          wall [[0 0 0] [2 0 0] [1 0 1]]
          reversed (vec (reverse a))
          m (mesh [a b b wall reversed])]
      (is (= [0 1 2] (:facet-indices (facet/select m 0))))
      (is (= [3] (:facet-indices (facet/select m 3))))
      (is (= [4] (:facet-indices (facet/select m 4)))))))

(deftest exact-float-edge-keys-test
  (testing "-0.0 and 0.0 are the same geometric point"
    (let [m (mesh [[[-0.0 0 0] [1 0 0] [0 1 0]]
                   [[1 0 0] [1 1 0] [0.0 1 0]]])]
      (is (= [0 1] (:facet-indices (facet/select m 0)))))))

(deftest recovery-uses-surface-distance-instead-of-centroid-distance
  (let [large-a [[-12 0 0] [-3 0 0] [-12 40 0]]
        large-b [[-3 0 0] [-3 40 0] [-12 40 0]]
        decoy [[-14 19 0] [-13 19 0] [-14 21 0]]
        m (mesh [large-a decoy large-b])]
    (is (= [0 2] (facet/match-frame m {:mount/pos [-7.5 20 0] :mount/axis [0 0 1]})))))

(deftest edge-normal-follows-rotated-and-subdivided-boundaries
  (let [angle (/ Math/PI 6.0)
        rotate (fn [[x y z]] [(- (* x (Math/cos angle)) (* y (Math/sin angle)))
                              (+ (* x (Math/sin angle)) (* y (Math/cos angle))) z])
        ;; Collinear vertices divide the long edge into four shorter segments.
        tris (vec (mapcat (fn [x] [[[x 0 0] [(inc x) 0 0] [x 2 0]]
                                   [[(inc x) 0 0] [(inc x) 2 0] [x 2 0]]]) (range 4)))
        rotated (mesh (mapv #(mapv rotate %) tris))
        {:keys [frame roll-source roll-ambiguous?]} (facet/select rotated 0)
        edge (rotate [1 0 0])]
    (is (= :boundary-edge-normal roll-source))
    (is (false? roll-ambiguous?))
    (is (< (abs (math/dot edge (:mount/roll frame))) 1e-6))
    (is (> (abs (math/dot (rotate [0 1 0]) (:mount/roll frame))) 0.999999))
    (is (= frame (:frame (facet/select rotated 7))))))

(deftest concave-boundary-does-not-use-a-hull-diagonal
  (let [m (mesh [[[0 0 0] [1 0 0] [0 1 0]]
                 [[1 0 0] [1 1 0] [0 1 0]]
                 [[1 0 0] [4 0 0] [1 1 0]]
                 [[4 0 0] [4 1 0] [1 1 0]]
                 [[0 1 0] [1 1 0] [0 4 0]]
                 [[1 1 0] [1 4 0] [0 4 0]]])
        frame (:frame (facet/select m 0))]
    (is (or (vec-close? [0 1 0] (:mount/roll frame))
            (vec-close? [1 0 0] (:mount/roll frame))))))

(deftest curved-face-selection
  (let [surface (mesh curved/triangles)]
    (is (= 2 (count (:facet-indices (facet/select surface 0)))))
    (is (= 4 (count (:facet-indices (facet/select surface 0 {:selection-angle-deg 25})))))
    (is (= 8 (count (:facet-indices (facet/select surface 0 {:selection-angle-deg 50})))))
    (is (= :invalid-angle (code-of #(facet/select surface 0 {:selection-angle-deg 91}))))
    (is (true? (geom/valid-frame? (:frame (facet/select surface 0 {:selection-angle-deg 50})))))))

(deftest spherical-cap-stays-bounded-by-the-clicked-normal
  (let [surface (mesh (fixtures/uv-sphere 5.0 12 24))
        result (facet/select surface 0 {:selection-angle-deg 50})]
    (is (> (count (:facet-indices result)) 2))
    (is (< (count (:facet-indices result)) (/ (:index-count surface) 6)))
    (is (geom/valid-frame? (:frame result))))
  (let [copy (mapv #(mapv (partial math/add [100.0 0.0 0.0]) %) curved/triangles)
        surface (mesh (into curved/triangles copy))]
    (is (= 8 (count (:facet-indices (facet/select surface 0 {:selection-angle-deg 50})))))))

(deftest split-seam-selection-test
  (let [surface (mesh seam/split-triangles)
        expected (vec (range 9))]
    (testing "both sides of a split seam select the complete face at zero angle"
      (doseq [seed [2 5] opts [nil {:selection-angle-deg 0} {:selection-angle-deg 50}]]
        (let [result (facet/select surface seed opts)]
          (is (= expected (:facet-indices result)))
          (is (vec-close? [0 0 0] (get-in result [:frame :mount/pos]))))))
    (testing "strict selection keeps point contacts, crossings and tiny gaps separate"
      (doseq [other [[[[0 0 12.5] [-2 0 13.5] [2 0 13.5]]]
                     [[[0 0 12.5001] [0 0 14] [2 0 12.5001]]]
                     [[[-1 0 -13] [-1 0 13] [1 0 0]]]]]
        (is (= expected (:facet-indices (facet/select (mesh (into seam/split-triangles other)) 2
                                                      {:selection-angle-deg 0 :seam-tolerance-mm 0}))))))))

(deftest seam-distance-selection-test
  (let [surface (mesh seam/gap-triangles)]
    (testing "the default crosses the Mech Cruiser's 0.002 mm gap at zero angle"
      (doseq [seed [2 6]]
        (is (= (vec (range 8)) (:facet-indices (facet/select surface seed {:selection-angle-deg 0}))))))
    (testing "zero and smaller-than-gap settings keep halves separate"
      (doseq [distance [0.0 0.001]]
        (is (= (vec (range 4)) (:facet-indices (facet/select surface 2 {:seam-tolerance-mm distance}))))))
    (testing "invalid distances are rejected"
      (doseq [distance [-1 0.101 Double/NaN Double/POSITIVE_INFINITY]]
        (is (= :invalid-seam-tolerance (code-of #(facet/select surface 2 {:seam-tolerance-mm distance}))))))))
