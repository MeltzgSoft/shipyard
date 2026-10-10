(ns shipyard.unit.regions.mirror-preparation-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.regions.mirror-preparation :as mirror]
            [shipyard.part.orientation :as orientation]))

(def sheet {:positions [10 20 30 12 20 30 10 22 30] :indices [0 1 2]})

(deftest projection-test
  (testing "Open sheets retain off-origin bounds and use their midpoint"
    (is (= {:bounds [[10.0 20.0 30.0] [12.0 22.0 30.0]] :offset 30.0}
           (mirror/projection sheet nil :z))))
  (testing "Orientation rotates precise vertices without discarding source translation"
    (let [q (orientation/from-euler-degrees 90 0 0)
          {:keys [bounds offset]} (mirror/projection sheet q :x)]
      (is (< (abs (- offset 30)) 1e-9))
      (is (< (abs (- (ffirst bounds) 30)) 1e-9))))
  (testing "Empty and degenerate geometry uses finite bounds fallback"
    (is (= {:bounds [[0 0 0] [0 0 0]] :offset 0.0}
           (mirror/projection {:positions [] :indices []} nil :y)))))

(deftest parse-orientation-test
  (testing "Only finite four-component quaternions are admitted"
    (is (= [0.0 0.0 0.0 1.0] (mirror/parse-orientation "[0 0 0 1]")))
    (is (nil? (mirror/parse-orientation "[1 2]")))
    (is (nil? (mirror/parse-orientation "garbage")))))
