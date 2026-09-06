(ns shipyard.mesh.float-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mesh.float :as mesh-float]))

(deftest canonical-bits-test
  (testing "folds negative zero onto positive zero"
    (is (= (mesh-float/canonical-bits -0.0)
           (mesh-float/canonical-bits 0.0))))
  (testing "preserves distinct float values"
    (is (not= (mesh-float/canonical-bits 1.0)
              (mesh-float/canonical-bits 2.0)))))
