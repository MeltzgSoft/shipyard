(ns shipyard.mesh.volume-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as fixtures]
            [shipyard.mesh.stl :as stl]
            [shipyard.mesh.volume :as volume]))

(deftest signed-test
  (testing "a closed cube has the expected volume magnitude"
    (let [{:keys [positions triangle-count]}
          (stl/parse-bytes (fixtures/->binary-stl (fixtures/cube 2.0)))]
      (is (= 8.0 (Math/abs (volume/signed positions triangle-count))))))
  (testing "an empty triangle soup has zero volume"
    (is (zero? (volume/signed (float-array 0) 0)))))
