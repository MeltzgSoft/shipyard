(ns shipyard.paint.emission-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.paint.emission :as emission]
            [shipyard.paint.faces :as faces]))

(def points [[0 0 0] [1 0 0] [0 1 0]])
(def mesh {:positions (float-array (apply concat points)) :indices (int-array [0 1 2 0 1 2])})

(deftest triangle-moment-test
  (testing "dominant positive Z and centroid weighted by triangle area"
    (is (= {:bucket 5 :sum [0.5 (/ 1.0 6) (/ 1.0 6) 0.0 0.0 0.0 0.5]}
           (emission/triangle-moment points))))
  (testing "opposite winding and degeneracy"
    (is (= 4 (:bucket (emission/triangle-moment (reverse points)))))
    (is (nil? (emission/triangle-moment [[0 0 0] [0 0 0] [1 0 0]])))))

(deftest source-moments-test
  (testing "duplicate source triangles share identity and retain both areas"
    (is (= 1 (count (emission/source-moments mesh))))
    (is (= 1.0 (get-in (emission/source-moments mesh) [(faces/face-key points) 5 0])))))

(deftest group-moments-test
  (let [source (emission/source-moments mesh) key (faces/face-key points)
        regions {:mesh-key "mesh" :faces {key "Secondary"}}
        details {:mesh-key "mesh" :faces {key {:base [1 0 0] :glow 1}}}]
    (testing "region selectors keep palette edits independent of geometry"
      (is (= "Secondary" (:layer (first (emission/group-moments source "mesh" regions nil))))))
    (testing "custom material overrides region and erase restores that region"
      (is (= {:base [1 0 0] :glow 1} (:detail (first (emission/group-moments source "mesh" regions details)))))
      (is (= "Secondary" (:layer (first (emission/group-moments source "mesh" regions (assoc details :faces {})))))))
    (testing "stale masks become unassigned Primary"
      (is (= "Primary" (:layer (first (emission/group-moments source "other" regions details))))))
    (testing "empty mesh has no summaries"
      (is (empty? (emission/group-moments {} "mesh" regions details))))))
