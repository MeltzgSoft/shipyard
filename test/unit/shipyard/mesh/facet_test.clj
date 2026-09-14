(ns shipyard.mesh.facet-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.math :as math]
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
      (is (vec-close? [1.0 0.0 0.0] (:mount/roll frame)))
      (is (finite-vec? (:mount/pos frame)))
      (is (close? 1.0 (math/length (:mount/axis frame))))
      (is (close? 1.0 (math/length (:mount/roll frame))))
      (is (close? 0.0 (math/dot (:mount/axis frame) (:mount/roll frame))))
      (is (false? roll-ambiguous?))
      (is (= :hull-edge roll-source))))

  (testing "a hard edge is adjacent but not part of the facet"
    (is (= [2 3] (:facet-indices (facet/select contract-mesh 2)))))

  (testing "disconnected coplanar triangles stay out"
    (is (= [4 5] (:facet-indices (facet/select contract-mesh 4)))))

  (testing "the same facet has the same durable frame regardless of which triangle is picked"
    (is (= (:frame (facet/select contract-mesh 0))
           (:frame (facet/select contract-mesh 1)))))

  (testing "squares use the deterministic roll fallback and say so"
    (let [{:keys [facet-indices frame roll-ambiguous? roll-source]} (facet/select contract-mesh 6)]
      (is (= [6 7] facet-indices))
      (is (vec-close? [1.0 1.0 3.0] (:mount/pos frame)))
      (is (vec-close? [0.0 0.0 1.0] (:mount/axis frame)))
      (is (vec-close? [1.0 0.0 0.0] (:mount/roll frame)))
      (is (true? roll-ambiguous?))
      (is (= :world-axis roll-source)))))

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
  (testing "a numerically bad hull projection still yields a selectable frame"
    (let [projected-points (ns-resolve 'shipyard.mesh.facet 'projected-points)
          {:keys [frame roll-ambiguous? roll-source]}
          (with-redefs-fn {projected-points (fn [_axis _points]
                                              (throw (NullPointerException. "bad projection")))}
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
  (testing "three triangles incident on one geometric edge are not unambiguous neighbours"
    (let [m (mesh [[[0 0 0] [2 0 0] [0 1 0]]
                   [[2 0 0] [0 0 0] [2 -1 0]]
                   [[0 0 0] [2 0 0] [1 0 1]]])]
      (is (= [0] (:facet-indices (facet/select m 0)))))))

(deftest exact-float-edge-keys-test
  (testing "-0.0 and 0.0 are the same geometric point"
    (let [m (mesh [[[-0.0 0 0] [1 0 0] [0 1 0]]
                   [[1 0 0] [1 1 0] [0.0 1 0]]])]
      (is (= [0 1] (:facet-indices (facet/select m 0)))))))
