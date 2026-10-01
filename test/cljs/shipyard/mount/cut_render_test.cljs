(ns shipyard.mount.cut-render-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [shipyard.mount.cut-render :as render]))

(def mount {:mount/pos [0 0 1] :mount/axis [0 0 1] :mount/roll [1 0 0]
            :mount/outline [[[-2 -2 1] [2 -2 1] [2 0 1] [0 0 1] [0 2 1] [-2 2 1]]]
            :mount/cut {:kind :recess :depth 0.5 :border 0.25}})

(deftest recess-rings-test
  (testing "live inset preserves the concave edge at the requested distance"
    (is (= #{[-1.75 -1.75] [1.75 -1.75] [1.75 -0.25] [-0.25 -0.25] [-0.25 1.75] [-1.75 1.75]}
           (set (first (render/recess-rings mount)))))
    (is (empty? (render/recess-rings (assoc-in mount [:mount/cut :border] 10.0))))))

(deftest lines-test
  (testing "recess floor has physical depth and holes use capacity centers"
    (let [lines (render/lines mount)]
      (is (= 18 (count lines)))
      (is (= #{0.5 1.0} (set (map last (apply concat lines))))))
    (let [pit (assoc mount :mount/cut {:kind :pit :depth 0.25 :diameter 0.4}
                     :mount/capacity 2 :mount/split {:direction :vertical :bounds [[-1 -1] [1 1]]})]
      (is (= (* 2 64 3) (count (render/lines pit)))))))

(deftest object-test
  (testing "wireframe always passes depth test and cannot hide a subsequent surface"
    (let [^js object (render/object! mount)]
      (is (= "mount-cut" (.-name object)))
      (is (false? (.. object -material -depthTest)))
      (is (false? (.. object -material -depthWrite)))
      (is (= 1000 (.-renderOrder object)))
      (.dispose (.-geometry object))
      (.dispose (.-material object)))))
