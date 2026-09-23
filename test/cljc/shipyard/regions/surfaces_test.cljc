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
    (is (= [#{0}] (surfaces/groups [[[0 0 0] [0 0 0] [0 0 0]]]))))
  (testing "small local slope changes cannot walk around a curved surface"
    (let [quad (fn [x z next-z]
                 [[[x 0 z] [(inc x) 0 next-z] [(inc x) 1 next-z]]
                  [[x 0 z] [(inc x) 1 next-z] [x 1 z]]])
          groups (surfaces/groups (vec (concat (quad 0 0 0) (quad 1 0 0.008) (quad 2 0.008 0.016))))]
      (is (= #{0 1 2 3} (first groups)))
      (is (= #{4 5} (last groups))))))
