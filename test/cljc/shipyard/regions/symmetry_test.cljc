(ns shipyard.regions.symmetry-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [shipyard.regions.symmetry :as symmetry]
            [shipyard.part.orientation :as orientation]))

(def left [[-2 0 0] [-2 0 2] [-2 2 0]])
(def right [[2 0 0] [2 2 0] [2 0 2]])

(deftest index-and-center-offset
  (let [index (symmetry/index [left right] nil)]
    (is (= 0.0 (symmetry/center-offset index :x)))
    (is (= 1.0 (symmetry/center-offset index :y)))
    (is (= 1.0 (symmetry/center-offset index :z))))
  (is (= 0.0 (symmetry/center-offset (symmetry/index [] nil) :x))))

(deftest counterparts
  (testing "reflected triangle, both directions, empty and unmatched selections"
    (let [index (symmetry/index [left right] nil)]
      (is (= #{1} (symmetry/counterparts index :x 0 0)))
      (is (= #{0} (symmetry/counterparts index :x nil 1)))
      (is (= #{} (symmetry/counterparts index :x 20 0)))
      (is (= #{} (symmetry/counterparts index :x 0 99))))
    (is (= #{} (symmetry/counterparts (symmetry/index [] nil) :x 0 0))))
  (testing "opposite diagonals match overlapping faces, without crossing adjacent edges"
    (let [triangles [left [[-2 0 2] [-2 2 2] [-2 2 0]]
                     [[2 0 0] [2 2 0] [2 2 2]] [[2 0 0] [2 2 2] [2 0 2]]
                     [[2 2 0] [2 4 0] [2 2 2]]]
          index (symmetry/index triangles nil)]
      (is (= #{2 3} (symmetry/counterparts index :x 0 0)))))
  (testing "offset models, all axes, and saved orientation"
    (doseq [axis [:x :y :z]
            :let [permute (case axis :x identity :y (fn [[x y z]] [z x y]) :z (fn [[x y z]] [y z x]))
                  shifted (mapv #(mapv (fn [p] (mapv + [8 8 8] (permute p))) %) [left right])
                  index (symmetry/index shifted nil)]]
      (is (= 8.0 (symmetry/center-offset index axis)))
      (is (= #{1} (symmetry/counterparts index axis nil 0)))
      (is (= #{1} (symmetry/counterparts index axis 8 0))))
    (let [q (orientation/rotate-around-world-axis nil :z 90)
          index (symmetry/index [left right] q)]
      (is (= #{1} (symmetry/counterparts index :y 0 0)))))
  (testing "degenerate, reversed and displaced surfaces are not guessed as matches"
    (let [index (symmetry/index [left (vec (reverse right)) [[2.01 0 0] [2.01 2 0] [2.01 0 2]]
                                 [[2 0 0] [2 0 0] [2 0 0]]] nil)]
      (is (= #{} (symmetry/counterparts index :x 0 0)))))
  (testing "center-plane triangles deduplicate and tree branches preserve all candidates"
    (let [center [[0 0 0] [1 0 0] [0 0 1]]
          triangles (vec (concat [left right center] (repeat 30 [[50 0 0] [50 1 0] [50 0 1]])))
          index (symmetry/index triangles nil)]
      (is (= #{1} (symmetry/counterparts index :x 0 0)))
      ;; The center-plane surface's normal reverses under this reflection, so it
      ;; remains in the original selection instead of gaining another face.
      (is (= #{} (symmetry/counterparts index :y 0 2))))))
