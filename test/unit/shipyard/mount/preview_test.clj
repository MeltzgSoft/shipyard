(ns shipyard.mount.preview-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.preview :as preview]
            [shipyard.pitting.geometry :as geometry]))

(def mount {:mount/pos [0.0 0.0 1.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]
            :mount/outline [[[-2.0 -2.0 1.0] [2.0 -2.0 1.0] [2.0 0.0 1.0]
                             [0.0 0.0 1.0] [0.0 2.0 1.0] [-2.0 2.0 1.0]]]
            :mount/cut {:kind :recess :depth 0.5 :border 0.25}})

(deftest lines-test
  (testing "concave recess preview uses the exact physical generation profiles"
    (let [profile (first (geometry/profiles mount)) lines (preview/lines mount)]
      (is (= #{[-1.75 -1.75] [1.75 -1.75] [1.75 -0.25] [-0.25 -0.25] [-0.25 1.75] [-1.75 1.75]}
             (set (first (:rings profile)))))
      (is (= 18 (count lines)))
      (is (= #{0.5 1.0} (set (map last (apply concat lines)))))))
  (testing "zero border keeps holes and disconnected selections"
    (let [cut (assoc mount :mount/outline
                     [[[-3.0 -3.0 1.0] [3.0 -3.0 1.0] [3.0 3.0 1.0] [-3.0 3.0 1.0]]
                      [[-1.0 -1.0 1.0] [-1.0 1.0 1.0] [1.0 1.0 1.0] [1.0 -1.0 1.0]]
                      [[5.0 0.0 1.0] [6.0 0.0 1.0] [6.0 1.0 1.0] [5.0 1.0 1.0]]])]
      (is (= 36 (count (preview/lines (assoc-in cut [:mount/cut :border] 0.0)))))))
  (testing "consuming border fails rather than inventing a preview"
    (is (thrown? clojure.lang.ExceptionInfo (preview/lines (assoc-in mount [:mount/cut :border] 10.0)))))
  (testing "multi-capacity pits reuse physical centering"
    (is (= (* 2 64 3)
           (count (preview/lines (assoc mount :mount/cut {:kind :pit :depth 0.25 :diameter 0.4}
                                        :mount/capacity 2 :mount/split {:direction :vertical :bounds [[-1.0 -1.0] [1.0 1.0]]})))))))

(deftest mirrored-capacity-drafts-preserve-the-physical-cut-projection
  (let [projection (preview/draft nil {:frame mount :capacity 2 :direction :vertical
                                       :cut {:kind :pit :depth 0.25 :diameter 0.4}
                                       :mirror {:plane :x :offset 3.0}} {})
        original (set (mapcat identity (:cuts projection)))
        rounded (fn [points] (set (map #(mapv (fn [n] (Math/round (* 1000000.0 n))) %) points)))
        reflected (rounded (mapcat identity (:mirror-cuts projection)))]
    (is (= (* 2 64 3) (count (:cuts projection))))
    (is (= (rounded (map (fn [[x y z]] [(- 6.0 x) y z]) original)) reflected))))
