(ns shipyard.regions.surfaces-test
  (:require [shipyard.regions.surfaces :as surfaces]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def square [[[0 0 0] [1 0 0] [1 1 0]] [[0 0 0] [1 1 0] [0 1 0]]])

(deftest connected-flat-surfaces
  (let [triangles (into square [[[1 1 0] [1 0 0] [1 0 1]]
                                [[3 0 0] [4 0 0] [3 1 0]]
                                [[0 1 0] [0 2 0] [-1 1 0]]])
        groups (surfaces/groups triangles)]
    (is (= [#{0 1} #{0 1} #{2} #{3} #{4}] groups)
        "Sharp edges, disconnected planes and point-only contacts remain separate")
    (is (= #{0 1} (surfaces/expand groups #{1})))
    (is (= #{0 1 2} (surfaces/expand groups #{0 2})))
    (is (= #{} (surfaces/expand groups #{})))))

(deftest surface-boundaries
  (testing "opposite normals do not form a single paint surface"
    (is (= [#{0} #{1}] (surfaces/groups [(first square) (vec (reverse (second square)))]))))
  (testing "non-manifold edges and degenerate triangles do not leak into another surface"
    (is (= [#{0} #{1} #{2}]
           (surfaces/groups (conj square [[0 0 0] [1 1 0] [2 0 0]]))))
    (is (= [#{0}] (surfaces/groups [[[0 0 0] [0 0 0] [0 0 0]]])))))

(deftest adjustable-curvature
  (let [quad (fn [[x z] [next-x next-z]]
               [[[x 0 z] [next-x 0 next-z] [next-x 1 next-z]]
                [[x 0 z] [next-x 1 next-z] [x 1 z]]])
        triangles (vec (mapcat (fn [[a b]] (quad a b))
                               (partition 2 1 [[0 0] [1 0] [2 0.2] [3 0.6] [4 1.8]])))]
    (is (= #{0 1} (first (surfaces/groups triangles 0))))
    (is (= #{0 1} (first (surfaces/groups triangles 10))))
    (is (= #{0 1 2 3 4 5} (first (surfaces/groups triangles 12)))
        "Neighbor-relative growth follows gradual curves but stops at the larger crease")
    (is (= (set (range 8)) (first (surfaces/groups triangles 30))))
    (is (= (set (range 8)) (first (surfaces/groups triangles 90))))))
