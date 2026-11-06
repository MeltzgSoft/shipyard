(ns shipyard.mesh.edges-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mesh.edges :as edges]
            [shipyard.mount-seam-fixture :as seam]
            [shipyard.mount-boundary-fixture :as boundary]))

(deftest split-edges-test
  (testing "a long edge is split at existing vertices, retaining winding"
    (let [a [0 0 0] m [0 1 0] b [0 2 0]]
      (is (= [[[a m] [m b]] [[b m]] [[m a]]]
             (edges/split-edges [[a b] [b m] [m a]])))))
  (testing "three-dimensional diagonals and wholly contained subedges"
    (let [a [0 0 0] m [1 2 3] n [2 4 6] b [3 6 9]]
      (is (= [[[a m] [m n] [n b]] [[n m]]]
             (edges/split-edges [[a b] [n m]])))))
  (testing "a projected intersection displaced in the third dimension is not connected"
    (is (= [[[[0 0 0] [4 4 0]]] [[[2 2 0.001] [2 3 0.001]]]]
           (edges/split-edges [[[0 0 0] [4 4 0]] [[2 2 0.001] [2 3 0.001]]]))))
  (testing "empty input"
    (is (= [] (edges/split-edges [])))))

(deftest outline-test
  (testing "split internal seams disappear from the wound outer boundary"
    (let [rings (edges/outline (subvec seam/split-triangles 0 9))]
      (is (= 1 (count rings)))
      (is (not-any? #{[0 0 0]} (first rings)))))
  (testing "non-manifold duplicate faces still reject an outline"
    (is (nil? (edges/outline (concat (subvec seam/split-triangles 0 9)
                                     (subvec seam/split-triangles 0 9)))))))

(deftest seam-distance-topology-test
  (testing "nearby endpoints close a tiny seam only in derived geometry"
    (is (= 2 (count (edges/outline seam/gap-triangles 0.0))))
    (is (= 1 (count (edges/outline seam/gap-triangles 0.005)))))
  (testing "fixed representatives prevent chains of near points extending the tolerance"
    (let [input [[[0 0 0] [0 2 0]] [[0.004 0 0] [0.004 2 0]] [[0.008 0 0] [0.008 2 0]]]
          result (edges/split-edges input 0.005)]
      (is (= (first result) (second result)))
      (is (not= (first result) (last result)))))
  (testing "a long seam subdivided on one side can also close a tiny gap"
    (let [a [0 0 0] b [0 2 0] c [0.002 0 0] m [0.002 1 0] d [0.002 2 0]
          result (edges/split-edges [[a b] [c m] [m d]] 0.005)]
      (is (= (set (first result)) (set (mapcat identity (rest result))))))))

(deftest connected-thin-triangulation-keeps-its-boundary
  (testing "a near-edge interior vertex does not break an existing complete outline"
    (let [strict (edges/outline boundary/triangles 0)]
      (is (= 1 (count strict)))
      (is (= strict (edges/outline boundary/triangles 0.005)))
      (is (= 4 (count (first strict))))))
  (testing "duplicate source faces can still lack an unambiguous cut boundary"
    (is (nil? (edges/outline boundary/duplicate-triangles 0.005)))))
