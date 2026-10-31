(ns shipyard.supported-spike.alignment-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.supported-spike.alignment :as alignment]
            [shipyard.geom :as geom]
            [shipyard.math :as math]
            [shipyard.part.orientation :as orientation]
            [shipyard.fixtures :as fixtures]
            [shipyard.mesh.volume :as volume]
            [shipyard.pitting.geometry :as geometry]))

(def matrix
  (assoc (geom/orientation-matrix (orientation/from-euler-degrees 29.0 -37.0 61.0))
         12 5.0 13 -7.0 14 11.0))

(defn mesh [triangles]
  {:positions (float-array (mapcat #(apply concat %) triangles)) :triangle-count (count triangles)})

(defn transformed [triangles]
  (mapv #(mapv (partial geom/transform-point matrix) %) triangles))

(def model
  (mapv (fn [i]
          (let [a [(* i 1.7) (double (mod i 3)) (* i 0.3)]]
            [a (math/add a [(+ 1.0 (* 0.17 i)) 0.1 0.0])
             (math/add a [0.2 (+ 2.0 (* 0.11 i)) 0.3])])) (range 18)))

(deftest canonical-triangle
  (let [tri (first model) item (alignment/canonical-triangle tri 0.01)]
    (is (= (:key item) (:key (alignment/canonical-triangle (vec (reverse tri)) 0.01))))
    (is (= (:key item) (:key (alignment/canonical-triangle (first (transformed [tri])) 0.01))))
    (is (nil? (alignment/canonical-triangle [[0 0 0] [1 0 0] [0 1 0]] 0.01)))))

(deftest rigid-transform
  (let [tri (first model) result (alignment/rigid-transform tri (first (transformed [tri])))]
    (is (every? #(< (abs %) 1.0e-8) (map - matrix result)))))

(deftest align
  (testing "arbitrary three-axis rotation and translation with added support faces"
    (let [supports (mapv #(mapv (partial math/add [31.0 17.0 -23.0]) %) (fixtures/cube 0.37))
          result (alignment/align (mesh model) (mesh (into (transformed model) supports)))]
      (is (= :candidate (:status result)))
      (is (>= (:inliers result) 6))
      (is (every? #(< (abs %) 1.0e-4) (map - matrix (:matrix result))))))
  (testing "unrelated meshes, scaling and repeated copies do not authorize a guess"
    (is (not= :candidate (:status (alignment/align (mesh model) (mesh (fixtures/cube 100))))))
    (is (not= :candidate (:status (alignment/align (mesh model) (mesh (mapv #(mapv (partial math/scale 1.3) %) model))))))
    (let [copy (transformed model)
          duplicate (mapv #(mapv (partial math/add [100.0 0.0 0.0]) %) copy)]
      (is (not= :candidate (:status (alignment/align (mesh model) (mesh (into copy duplicate)))))))))

(deftest transfer-mount
  (let [mount {:mount/pos [1.0 2.0 0.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]
               :mount/cut {:kind :pit :diameter 1.5 :depth 0.8 :mesh-key "original"}
               :mount/facet {:indices [7 8]} :mount/outline [[[-1.0 -1.0 0.0] [1.0 -1.0 0.0] [0.0 1.0 0.0]]]}
        result (alignment/transfer-mount matrix mount)]
    (is (= (geom/transform-point matrix (:mount/pos mount)) (:mount/pos result)))
    (is (geom/valid-frame? result))
    (is (= (transformed (get mount :mount/outline)) (:mount/outline result)))
    (is (= {:kind :pit :diameter 1.5 :depth 0.8} (:mount/cut result)))
    (is (not (contains? result :mount/facet)))))

(deftest transformed-cuts-preserve-supports
  (let [a [0.0 0.0 0.0] b [5.0 0.0 0.0] c [0.0 3.0 0.0] d [0.0 0.0 4.0]
        tetra [[a c b] [a b d] [a d c] [b c d]]
        supports (mapv #(mapv (partial math/add [8.0 8.0 -3.0]) %) (fixtures/cube 1.0))
        supported (transformed (into tetra supports))
        result (alignment/align (mesh tetra) (mesh supported) {:minimum-inliers 3})
        inverse (alignment/rigid-transform (first (transformed tetra)) (first tetra))
        signed (fn [triangles] (let [m (mesh triangles)] (volume/signed (:positions m) (:triangle-count m))))]
    (is (= :candidate (:status result)))
    (doseq [cut [{:kind :pit :diameter 0.5 :depth 0.2} {:kind :recess :border 0.3 :depth 0.2}]]
      (let [mount {:mount/pos [1.0 1.0 0.0] :mount/axis [0.0 0.0 -1.0] :mount/roll [1.0 0.0 0.0]
                   :mount/outline [[a c b]] :mount/cut cut}
            original-cut (geometry/subtract (mesh tetra) [mount])
            supported-cut (geometry/subtract (mesh supported) [(alignment/transfer-mount (:matrix result) mount)])
            back (mapv #(mapv (partial geom/transform-point inverse) %) supported-cut)
            remaining-supports (filter #(every? (fn [[x _ _]] (> x 7.0)) %) back)]
        (is (< (abs (- (- (signed tetra) (signed original-cut))
                       (- (signed supported) (signed supported-cut)))) 1.0e-4))
        (is (< (abs (- 1.0 (signed remaining-supports))) 1.0e-4))))))
