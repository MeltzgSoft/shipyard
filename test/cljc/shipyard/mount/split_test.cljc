(ns shipyard.mount.split-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.split :as split]))

(def socket {:mount/pos [0 0 0] :mount/axis [0 0 1] :mount/roll [1 0 0]
             :mount/capacity 2
             :mount/split {:direction :vertical :bounds [[-4 -2] [4 2]]}})

(deftest face-bounds-test
  (testing "project translated source points onto the face frame"
    (is (= [[-4 -2] [4 2]]
           (split/face-bounds socket [[-4 -2 0] [4 2 0]])))
    (is (nil? (split/face-bounds socket [])))))

(deftest valid-bounds?-test
  (testing "finite positive width and height"
    (is (split/valid-bounds? [[-4 -2] [4 2]]))
    (doseq [bad [nil [] [[0 0] [0 1]] [[1 1] [0 0]] [[0 0] [##Inf 1]]]]
      (is (false? (split/valid-bounds? bad))))))

(deftest sections-test
  (testing "equal width vertical sections and boundary"
    (let [result (split/sections socket)]
      (is (= [[-2.0 0.0 0.0] [2.0 0.0 0.0]] (mapv :mount/pos (:frames result))))
      (is (= [[[0.0 -2.0 0.0] [0.0 2.0 0.0]]] (:lines result)))))
  (testing "equal height horizontal sections"
    (let [result (split/sections (assoc-in socket [:mount/split :direction] :horizontal))]
      (is (= [[0.0 -1.0 0.0] [0.0 1.0 0.0]] (mapv :mount/pos (:frames result))))
      (is (= [[[-4.0 0.0 0.0] [4.0 0.0 0.0]]] (:lines result)))))
  (testing "single and malformed legacy capacity"
    (is (= [(assoc socket :mount/capacity 1)] (:frames (split/sections (assoc socket :mount/capacity 1)))))
    (is (= :incomplete-split (:error (split/sections (dissoc socket :mount/split)))))
    (is (= :invalid-capacity (:error (split/sections (assoc socket :mount/capacity 0))))))
  (testing "rotated mount uses authored axes"
    (is (= [[0.0 0.0 -2.0] [0.0 0.0 2.0]]
           (mapv :mount/pos (:frames (split/sections
                                      (assoc socket :mount/axis [1 0 0] :mount/roll [0 0 1]))))))))
