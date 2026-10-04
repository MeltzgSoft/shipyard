(ns shipyard.mount.facet-input-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.facet-input :as input]))

(deftest parse-indices-test
  (testing "only bounded, non-negative index vectors cross the transport boundary"
    (is (= [0 3] (input/parse-indices "[0 3]")))
    (is (nil? (input/parse-indices "[-1]")))
    (is (nil? (input/parse-indices "[0 0]")))
    (is (nil? (input/parse-indices "[]")))
    (is (nil? (input/parse-indices "{:not \"a vector\"}")))
    (is (nil? (input/parse-indices "[999999999999999999999]"))))
  (testing "the selected indices must exist in the tier-zero mesh"
    (is (input/in-mesh? {:index-count 6} [0 1]))
    (is (not (input/in-mesh? {:index-count 6} [2])))))

(deftest in-facet-test
  (let [mesh {:positions (float-array [0 0 0 4 0 0 0 2 0 4 2 0 6 0 0 8 0 0 6 1 0])
              :indices (int-array [0 1 2 1 3 2 4 5 6]) :index-count 9}]
    (is (input/in-facet? mesh [1] nil))
    (is (input/in-facet? mesh [0 1] nil))
    (is (not (input/in-facet? mesh [0 2] nil)))
    (is (not (input/in-facet? mesh [] nil)))
    (is (not (input/in-facet? mesh [3] nil)))))
