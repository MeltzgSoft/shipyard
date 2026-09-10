(ns shipyard.mount.facet-input-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.facet-input :as input]))

(deftest parse-indices-test
  (testing "only bounded, non-negative index vectors cross the transport boundary"
    (is (= [0 3] (input/parse-indices "[0 3]")))
    (is (nil? (input/parse-indices "[-1]")))
    (is (nil? (input/parse-indices "{:not \"a vector\"}")))
    (is (nil? (input/parse-indices "[999999999999999999999]"))))
  (testing "the selected indices must exist in the tier-zero mesh"
    (is (input/in-mesh? {:index-count 6} [0 1]))
    (is (not (input/in-mesh? {:index-count 6} [2])))))
