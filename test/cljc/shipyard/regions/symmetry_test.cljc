(ns shipyard.regions.symmetry-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [shipyard.regions.symmetry :as symmetry]))

(defn transform [matrix point w]
  (mapv (fn [row]
          (+ (* (nth matrix row) (nth point 0))
             (* (nth matrix (+ row 4)) (nth point 1))
             (* (nth matrix (+ row 8)) (nth point 2))
             (* (nth matrix (+ row 12)) w)))
        (range 3)))

(deftest center-offset
  (is (= [3.0 6.0 9.0]
         (mapv #(symmetry/center-offset [[2 4 6] [4 8 12]] %) [:x :y :z]))))

(deftest reflection-matrix
  (doseq [[axis coordinate] [[:x 0] [:y 1] [:z 2]]]
    (testing (str "Reflection across " axis " preserves the other coordinates and ray distance")
      (let [bounds [[2 4 6] [4 8 12]]
            point [7 8 9] direction [1 2 3]
            matrix (symmetry/reflection-matrix bounds axis nil)
            reflected (transform matrix point 1)]
        (is (= (mapv double (assoc point coordinate (- (* 2 (symmetry/center-offset bounds axis)) (nth point coordinate))))
               (mapv double reflected)))
        (is (= (mapv double point) (mapv double (transform matrix reflected 1))))
        (is (= (mapv double (assoc direction coordinate (- (nth direction coordinate))))
               (mapv double (transform matrix direction 0))))
        (doseq [offset [0 -5 10]]
          (let [matrix (symmetry/reflection-matrix bounds axis offset)]
            (is (= (assoc point coordinate (- (* 2 offset) (nth point coordinate))) (transform matrix point 1)))
            (is (= (assoc point coordinate offset) (transform matrix (assoc point coordinate offset) 1)))))))))

(deftest plane-guide
  (let [bounds [[2 4 6] [4 8 12]]]
    (doseq [[axis normal size] [[:x [1 0 0] [7.2 4.8]]
                                [:y [0 1 0] [2.4 7.2]]
                                [:z [0 0 1] [2.4 4.8]]]]
      (let [guide (symmetry/plane-guide bounds axis nil)]
        (is (= [3.0 6.0 9.0] (:position guide)))
        (is (= normal (:normal guide)))
        (is (every? #(< (abs %) 1e-9) (map - size (:size guide))))))
    (is (= [3.0 -5 9.0] (:position (symmetry/plane-guide bounds :y -5))))
    (is (= [3.0 6.0 0] (:position (symmetry/plane-guide bounds :z 0)))))
  (is (every? pos? (:size (symmetry/plane-guide [[0 0 0] [0 0 0]] :x nil)))))
