(ns shipyard.mount.facet-input-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.facet-input :as input]
            [shipyard.mount-angle-fixture :as curved]))

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

(deftest angular-selection-options
  (is (= {:facet-angle-deg 1} (input/selection-options {:facet-angle-deg 1} {})))
  (is (= {:selection-angle-deg 25.5 :selection-seed 7}
         (input/selection-options {} {"face-angle" "25.5" "face-seed" "7"})))
  (is (= {:selection-angle-deg 0.0 :selection-seed 0}
         (input/selection-options {} {:face-angle 0 :face-seed 0})))
  (doseq [value ["NaN" "Infinity" "-1" "91" "bad"]]
    (is (false? (input/valid-angle? value))))
  (doseq [value ["0" "25.5" "90"]]
    (is (true? (input/valid-angle? value)))))

(deftest curved-trimming-keeps-the-original-seed
  (let [positions (float-array (apply concat (apply concat curved/triangles)))
        n (* 3 (count curved/triangles))
        mesh {:positions positions :indices (int-array (range n)) :index-count n}
        opts {:selection-angle-deg 20 :selection-seed 0}]
    (is (input/in-facet? mesh [2 3] opts))
    (is (input/in-facet? mesh [4 5] opts))
    (is (input/in-facet? mesh [0 11] opts))
    (is (not (input/in-facet? mesh [0 11] (assoc opts :selection-rule :seed))))))

(deftest seam-selection-options
  (is (= {:seam-tolerance-mm 0.002} (input/selection-options {:seam-tolerance-mm 0.005} {"face-seam" "0.002"}))))

(deftest retained-angle-rule-options
  (is (= {:selection-rule :seed} (input/selection-options {} {"face-rule" "seed"})))
  (is (= {:selection-rule :adjacent} (input/selection-options {} {:face-rule :adjacent}))))
