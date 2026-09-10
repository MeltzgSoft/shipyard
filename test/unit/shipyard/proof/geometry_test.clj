(ns shipyard.proof.geometry-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.proof.geometry :as geometry]))

(def a [0.0 0.0 0.0])
(def b [2.0 0.0 0.0])
(def c [0.0 2.0 0.0])

(deftest closest-point-on-triangle-test
  (testing "points above a face project onto its interior"
    (is (= [0.5 0.5 0.0] (geometry/closest-point-on-triangle [0.5 0.5 3.0] a b c))))
  (testing "points beyond an edge and vertex clamp to that feature"
    (is (= [1.0 0.0 0.0] (geometry/closest-point-on-triangle [1.0 -1.0 0.0] a b c)))
    (is (= b (geometry/closest-point-on-triangle [4.0 -1.0 0.0] a b c)))))

(deftest nearest-surface-test
  (let [mesh {:triangle-count 2
              :positions (float-array [0 0 0 2 0 0 0 2 0
                                       0 0 4 2 0 4 0 2 4])}]
    (testing "the nearest source triangle and its normal are reported"
      (let [{:keys [triangle point distance-mm normal]}
            (geometry/nearest-surface mesh [0.5 0.5 3.0])]
        (is (= 1 triangle))
        (is (= [0.5 0.5 4.0] point))
        (is (= 1.0 distance-mm))
        (is (= [0.0 0.0 1.0] normal))))))
