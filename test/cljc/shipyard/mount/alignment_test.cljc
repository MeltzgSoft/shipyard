(ns shipyard.mount.alignment-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.alignment :as alignment]
            [shipyard.mount.split :as split]))

(def mount {:mount/pos [1.0 2.0 3.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]})

(deftest valid?-test
  (testing "absence is legacy; only authored horizontal/vertical axes are accepted"
    (is (alignment/valid? mount))
    (doseq [axis [:horizontal :vertical]] (is (alignment/valid? (assoc mount :mount/alignment-axis axis))))
    (doseq [axis [nil :none :diagonal "horizontal"]]
      (is (false? (alignment/valid? (assoc mount :mount/alignment-axis axis)))))))

(deftest direction-test
  (is (nil? (alignment/direction mount)))
  (is (= [1.0 0.0 0.0] (alignment/direction (assoc mount :mount/alignment-axis :horizontal))))
  (is (= [0.0 1.0 0.0] (alignment/direction (assoc mount :mount/alignment-axis :vertical))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (alignment/direction (assoc mount :mount/alignment-axis :bad)))))

(deftest line-test
  (is (nil? (alignment/line mount 2.0)))
  (is (= [[-1.0 2.0 3.0] [3.0 2.0 3.0]] (alignment/line (assoc mount :mount/alignment-axis :horizontal) 2.0)))
  (is (= [[1.0 0.0 3.0] [1.0 4.0 3.0]] (alignment/line (assoc mount :mount/alignment-axis :vertical) 2.0))))

(deftest capacity-sections-retain-alignment-test
  (let [socket (assoc mount :mount/alignment-axis :vertical :mount/capacity 2
                      :mount/split {:direction :vertical :bounds [[-2.0 -1.0] [2.0 1.0]]})
        sections (:frames (split/sections socket))]
    (is (= [:vertical :vertical] (mapv :mount/alignment-axis sections)))
    (is (= [[0.0 1.0 0.0] [0.0 1.0 0.0]] (mapv alignment/direction sections)))
    (is (= [[0.0 2.0 3.0] [2.0 2.0 3.0]] (mapv :mount/pos sections)))))
