(ns shipyard.paint.projection-job-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.paint.projection-job :as job]))

(deftest appearance-test
  (testing "ordinal masks retain repeated identities, layer and custom material precedence"
    (let [material {:base [1 0 0] :metalness 0 :roughness 1}
          result (job/appearance ["a" "b" "a"] "mesh"
                                 {:mesh-key "mesh" :revision 5 :faces {"a" "Secondary"}}
                                 {:mesh-key "mesh" :faces {"b" material}})]
      (is (= [1 0 1] (:layers result)))
      (is (= [0 1 0] (:details result)))
      (is (= "5" (get-in result [:metadata :region-revision])))
      (is (= [nil material] (get-in result [:metadata :detail-table])))))
  (testing "stale sources clear masks"
    (is (= [0] (:layers (job/appearance ["a"] "other" {:mesh-key "mesh" :faces {"a" "Secondary"}} nil))))))

(deftest ordered-keys-test
  (testing "duplicates preserve exact tier-0 ordinals"
    (let [keys (job/ordered-keys {:positions (float-array [0 0 0 1 0 0 0 1 0]) :indices (int-array [0 1 2 0 1 2])})]
      (is (= 2 (count keys)))
      (is (= (first keys) (second keys))))))
