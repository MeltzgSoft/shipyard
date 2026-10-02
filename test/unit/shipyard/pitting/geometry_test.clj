(ns shipyard.pitting.geometry-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.math :as math]
            [shipyard.mesh.stl :as stl]
            [shipyard.mount.cut :as cut]
            [shipyard.mount.wizard :as wizard]
            [shipyard.pitting.geometry :as geometry]))

(def frame {:mount/pos [0.0 0.0 1.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]})
(def cube (stl/parse-bytes (f/->binary-stl (f/cube 2.0))))
(defn volume [triangles]
  (abs (/ (reduce + (map (fn [[a b c]] (math/dot a (math/cross b c))) triangles)) 6.0)))
(defn near? [a b] (< (abs (- a b)) 0.001))

(deftest subtract-test
  (testing "a cylindrical pit has the requested depth and diameter"
    (let [mount (assoc frame :mount/cut {:kind :pit :depth 0.5 :diameter 0.5})
          result (geometry/subtract cube [mount])]
      (is (near? (- 8.0 (* Math/PI 0.25 0.25 0.5)) (volume result)))
      (is (some #(near? 0.5 (last %)) (apply concat result)))))
  (testing "capacity and mirrored mount remove independent volumes"
    (let [mount (assoc frame :mount/pos [0.5 0.0 1.0] :mount/id :socket :mount/capacity 2
                       :mount/split {:direction :horizontal :bounds [[-0.2 -0.8] [0.2 0.8]]}
                       :mount/cut {:kind :pit :depth 0.4 :diameter 0.3})
          mirror (wizard/mirror-mount mount :x 0.0 :mirror)
          result (geometry/subtract cube [mount mirror])]
      (is (near? (- 8.0 (* 4 Math/PI 0.15 0.15 0.4)) (volume result)))))
  (testing "a recess follows its face inset and cuts inward"
    (let [mount (assoc frame :mount/outline [[[-1.0 -1.0 1.0] [1.0 -1.0 1.0] [1.0 1.0 1.0] [-1.0 1.0 1.0]]]
                       :mount/cut {:kind :recess :depth 0.5 :border 0.25})]
      (is (near? (- 8.0 (* 1.5 1.5 0.5)) (volume (geometry/subtract cube [mount]))))))
  (testing "disabled cuts leave original geometry"
    (is (near? 8.0 (volume (geometry/subtract cube [frame]))))))

(deftest inset-test
  (testing "a concave outline retains its notch instead of filling a bounding rectangle"
    (let [ring [[-2 -2 1] [2 -2 1] [2 0 1] [0 0 1] [0 2 1] [-2 2 1]]
          shape (geometry/inset (assoc frame :mount/outline [ring] :mount/cut {:border 0.25}))]
      (is (near? 8.25 (.getArea shape)))))
  (testing "oversized border is actionable"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"border consumes"
                          (geometry/inset (assoc frame :mount/outline [[[-1 -1 1] [1 -1 1] [1 1 1] [-1 1 1]]]
                                                 :mount/cut {:border 2.0}))))))

(deftest profiles-test
  (testing "outline from triangle selection can drive recess geometry"
    (let [outline (cut/outline [[[-1 -1 1] [1 -1 1] [-1 1 1]] [[1 -1 1] [1 1 1] [-1 1 1]]])
          profiles (geometry/profiles (assoc frame :mount/outline outline :mount/cut {:kind :recess :border 0.25}))]
      (is (= 1 (count profiles)))
      (is (= 4 (count (get-in profiles [0 :rings 0])))))))

(deftest binary-stl-test
  (testing "generated geometry round-trips through the application's STL reader"
    (let [bytes (geometry/binary-stl (geometry/mesh-triangles cube))
          read-back (stl/parse-bytes bytes)]
      (is (= 12 (:triangle-count read-back)))
      (is (= (vec (:positions cube)) (vec (:positions read-back)))))))

(deftest closed-source?-test
  (testing "closed solids are accepted; open, duplicate or reversed triangles are rejected"
    (let [triangles (geometry/mesh-triangles cube)]
      (is (geometry/closed-source? triangles))
      (is (not (geometry/closed-source? (rest triangles))))
      (is (not (geometry/closed-source? (conj triangles (first triangles)))))
      (is (not (geometry/closed-source? (assoc triangles 0 (vec (reverse (first triangles)))))))
      (is (not (geometry/closed-source? (mapv (comp vec reverse) triangles))))
      (is (not (geometry/closed-source? []))))))
