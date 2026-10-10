(ns shipyard.paint.picking-test
  (:require ["three" :as three]
            [cljs.test :refer-macros [deftest is testing]]
            [shipyard.paint.picking :as picking]))

(deftest range-end-test
  (testing "ID zero stays reserved; the maximum 24-bit ID is admitted"
    (is (= 16777216 (picking/range-end 1 16777215)))
    (is (= 16777216 (picking/range-end 16777215 1)))
    (is (thrown? js/Error (picking/range-end 16777215 2)))))

(deftest live-source-geometry-test
  (let [positions (js/Float32Array. #js [0 0 0 1 0 0 0 1 0])
        topology #js {:positions positions :count 1}
        source (three/BufferGeometry.) a (three/Mesh. source) b (three/Mesh. source)]
    (set! (.. source -userData -preparedTopology) topology)
    (testing "Repeated source instances share one immutable position geometry and implicit ordinals"
      (let [geometry (picking/geometry! a) disposed (atom 0)]
        (.addEventListener geometry "dispose" (fn [_] (swap! disposed inc)))
        (is (identical? geometry (picking/geometry! a)))
        (is (identical? geometry (picking/geometry! b)))
        (is (identical? positions (.-array (.getAttribute geometry "position"))))
        (is (nil? (.getAttribute geometry "faceId")))
        (is (= 2 (.-pickingUsers topology)))
        (picking/dispose-object! a)
        (is (zero? @disposed))
        (picking/dispose-object! b)
        (is (= 1 @disposed))
        (is (nil? (.-pickingGeometry topology)))
        (picking/dispose-object! b)
        (is (= 1 @disposed))))))
