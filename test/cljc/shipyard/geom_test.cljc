(ns shipyard.geom-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.geom :as geom]
            [shipyard.math :as math]
            [shipyard.part.orientation :as orientation]))

(def frame {:mount/pos [0.0 0.0 0.0]
            :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]})

(defn close? [a b]
  (and (= (count a) (count b))
       (every? #(<= (abs (double %)) geom/tolerance) (map - a b))))

(defn error-code [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) e (:code (ex-data e)))))

(deftest valid-frame?-test
  (testing "orthonormal frames and finite positions"
    (is (geom/valid-frame? frame))
    (doseq [bad [nil (assoc frame :mount/pos [0 0 ##Inf])
                 (assoc frame :mount/axis [0 0 2])
                 (assoc frame :mount/roll [0 0 1])
                 (assoc frame :mount/roll [##NaN 0 0])]]
      (is (false? (geom/valid-frame? bad))))))

(deftest frame-matrix-test
  (testing "identity and translated rotated right-handed basis"
    (is (= geom/identity-matrix (geom/frame-matrix frame)))
    (let [m (geom/frame-matrix (assoc frame :mount/pos [2 3 4]
                                      :mount/axis [1 0 0] :mount/roll [0 0 -1]))]
      (is (close? [2 3 3] (geom/transform-point m [1 0 0])))
      (is (close? [2 4 4] (geom/transform-point m [0 1 0])))))
  (testing "bad authoring is actionable"
    (is (= :invalid-frame (error-code #(geom/frame-matrix {}))))))

(deftest multiply-test
  (testing "identity and ordered translation composition"
    (let [a (geom/frame-matrix (assoc frame :mount/pos [1 2 3]))]
      (is (close? a (geom/multiply geom/identity-matrix a)))
      (is (close? [2 4 6] (geom/transform-point (geom/multiply a a) [0 0 0])))))
  (testing "malformed matrices"
    (is (= :invalid-matrix (error-code #(geom/multiply [] geom/identity-matrix))))))

(deftest transform-point-test
  (testing "finite points including origin"
    (is (close? [1 2 3] (geom/transform-point geom/identity-matrix [1 2 3])))
    (is (close? [0 0 0] (geom/transform-point geom/identity-matrix [0 0 0]))))
  (testing "reject invalid input"
    (is (= :invalid-point (error-code #(geom/transform-point geom/identity-matrix [0 ##Inf 0]))))))

(deftest inverse-frame-test
  (testing "inverse of rotated translated frame in both directions"
    (let [f (assoc frame :mount/pos [8 -3 2] :mount/axis [1 0 0] :mount/roll [0 0 -1])
          m (geom/frame-matrix f) inv (geom/inverse-frame f)]
      (is (close? geom/identity-matrix (geom/multiply m inv)))
      (is (close? geom/identity-matrix (geom/multiply inv m))))
    (is (= :invalid-frame (error-code #(geom/inverse-frame nil))))))

(deftest orientation-matrix-test
  (testing "missing means identity and root yaw rotates source coordinates"
    (is (= geom/identity-matrix (geom/orientation-matrix nil)))
    (is (close? [1 0 0]
                (geom/transform-point
                 (geom/orientation-matrix (orientation/from-euler-degrees 90 0 0)) [0 0 1]))))
  (testing "malformed orientation is not silently identity"
    (doseq [q [[] [0 0 0 0] [0 ##NaN 0 1]]]
      (is (= :invalid-orientation (error-code #(geom/orientation-matrix q)))))))

(deftest attachment-matrix-test
  (testing "flush mating chooses the global yaw that makes face normals oppose and supports a gap"
    (let [socket (assoc frame :mount/pos [4 5 6])
          plug (assoc frame :mount/pos [100 20 30])
          m (geom/attachment-matrix geom/identity-matrix socket plug)]
      (is (close? [4 5 6] (geom/transform-point m (:mount/pos plug))))
      (is (close? [4 5 5] (geom/transform-point m [100 20 31])))
      (is (close? [3 5 6] (geom/transform-point m [101 20 30])))
      (is (close? [4 6 6] (geom/transform-point m [100 21 30])))
      (is (close? [4 5 8] (geom/transform-point
                           (geom/attachment-matrix geom/identity-matrix socket plug 2) [100 20 30])))))
  (testing "canonical root and nested placement align each pair of mount faces"
    (let [root (geom/orientation-matrix (orientation/from-euler-degrees 90 0 0))
          weapon (geom/attachment-matrix root (assoc frame :mount/pos [0 0 10]) frame)
          turret (geom/attachment-matrix weapon (assoc frame :mount/pos [0 0 2]) frame)]
      (is (close? [10 0 0] (geom/transform-point weapon [0 0 0])))
      (is (close? [8 0 0] (geom/transform-point turret [0 0 0])))
      (is (close? [-1 0 0]
                  (math/normalize
                   (math/subtract (geom/transform-point weapon [0 0 1])
                                  (geom/transform-point weapon [0 0 0])))))
      (is (close? [1 0 0]
                  (math/normalize
                   (math/subtract (geom/transform-point turret [0 0 1])
                                  (geom/transform-point turret [0 0 0])))))))
  (testing "the configured pose is preserved except for its face-aligning global yaw"
    (let [q (orientation/from-euler-degrees 30 0 0)
          plug (assoc frame :mount/pos [2 3 4])
          m (geom/attachment-matrix geom/identity-matrix frame plug q 0.0)
          origin (geom/transform-point m [0.0 0.0 0.0])]
      (is (close? (:mount/pos frame) (geom/transform-point m (:mount/pos plug))))
      (is (close? [0 1 0]
                  (math/subtract (geom/transform-point m [0.0 1.0 0.0]) origin)))))
  (testing "vertical joins point the child toward the assembled parent's forward direction"
    (let [vertical-parent (assoc frame :mount/axis [0.0 1.0 0.0])
          vertical-child (assoc frame :mount/axis [0.0 -1.0 0.0])
          parent (geom/orientation-matrix (orientation/from-euler-degrees 90 0 0))
          child (geom/attachment-matrix parent vertical-parent vertical-child)
          parent-forward (math/normalize
                          (math/subtract (geom/transform-point parent [0.0 0.0 1.0])
                                         (geom/transform-point parent [0.0 0.0 0.0])))
          child-forward (math/normalize
                         (math/subtract (geom/transform-point child [0.0 0.0 1.0])
                                        (geom/transform-point child [0.0 0.0 0.0])))]
      (is (close? parent-forward child-forward))
      (is (close? (:mount/pos vertical-parent)
                  (geom/transform-point child (:mount/pos vertical-child))))))
  (testing "incompatible pitch or roll authoring is rejected instead of creating an intersecting join"
    (is (= :incompatible-mount-orientation
           (error-code #(geom/attachment-matrix geom/identity-matrix frame frame
                                                (orientation/from-euler-degrees 30 20 10) 0.0)))))
  (testing "invalid gaps and frames"
    (is (= :invalid-gap (error-code #(geom/attachment-matrix geom/identity-matrix frame frame ##Inf))))
    (is (= :invalid-frame (error-code #(geom/attachment-matrix geom/identity-matrix {} frame))))))
