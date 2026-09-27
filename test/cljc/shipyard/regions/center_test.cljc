(ns shipyard.regions.center-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [shipyard.regions.center :as center]))

(defn sheet [x size]
  [[[x (- size) (- size)] [x size (- size)] [x size size]]
   [[x (- size) (- size)] [x size size] [x (- size) size]]])

(deftest opposing-surfaces-ignore-small-asymmetric-details
  (let [walls (concat (sheet 2 1) (sheet 4 1))
        ;; A small protrusion changes the bounding midpoint from 3 to 4.
        triangles (concat walls (sheet 6 0.1))]
    (doseq [[axis order bounds] [[:x [0 1 2] [[2 -1 -1] [6 1 1]]]
                                 [:y [1 0 2] [[-1 2 -1] [1 6 1]]]
                                 [:z [1 2 0] [[-1 -1 2] [1 1 6]]]]]
      (let [rotated (map (fn [t] (mapv #(mapv % order) t)) triangles)]
        (is (= 3.0 (center/estimate rotated bounds axis)))))
    (is (= 3.0 (center/estimate (reverse triangles) [[2 -1 -1] [6 1 1]] :x)))
    (is (= 3.0 (center/estimate (concat triangles (mapcat identity (repeat 100 (sheet 6 0.1))))
                                [[2 -1 -1] [6 1 1]] :x))
        "Triangle density does not weight the estimate")))

(deftest slope-and-retessellation-preserve-center
  (let [wall (sheet 2 1)
        midpoint (fn [a b] (mapv #(/ (+ %1 %2) 2) a b))
        subdivide (fn [[a b c]] (let [ab (midpoint a b) bc (midpoint b c) ca (midpoint c a)]
                                  [[a ab ca] [ab b bc] [ca bc c] [ab bc ca]]))
        slope (fn [[x y z]] [(+ x (* 0.1 y)) y z])
        reflect (fn [[x y z]] [(- 6 x) y z])
        near (map #(mapv slope %) wall)
        far (map #(mapv reflect %) (mapcat subdivide near))]
    (is (< (abs (- 3 (center/estimate (concat near far) [[1.9 -1 -1] [4.1 1 1]] :x))) 1e-9))))

(deftest no-opposing-surfaces-use-bounds
  (is (= 3.0 (center/estimate [] [[2 -1 -1] [4 1 1]] :x)))
  (is (= 2.0 (center/estimate (sheet 2 1) [[2 -1 -1] [2 1 1]] :x)))
  (is (= 3.0 (center/estimate [] [[2 0 0] [4 0 0]] :x))))
