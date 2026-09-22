(ns shipyard.paint.brush-test
  (:require [cljs.test :refer-macros [deftest is]]
            [shipyard.paint.brush :as brush]
            [shipyard.paint.faces :as faces]))

(deftest circular-pixel-boundary-and-target-range
  ;; GPU readback rows run bottom to top. IDs 1..9 are unique visible triangles;
  ;; range [4,7) admits only three of them from this instance.
  (let [pixels (js/Uint8Array. 36)
        buffer {:pixels pixels :width 3 :height 3 :start 4 :end 7}]
    (dotimes [n 9] (aset pixels (* n 4) (inc n)))
    (is (= #{1} (brush/visible-triangles buffer 1.5 1.5 0.49)))
    (is (= #{0 1 2} (brush/visible-triangles buffer 1.5 1.5 1)))
    (is (= #{} (brush/visible-triangles buffer -5 -5 1)))
    (is (= #{0 1 2} (brush/visible-triangles buffer 1.5 1.5 100)))
    (is (= #{} (brush/visible-triangles (dissoc buffer :start) 1.5 1.5 100)))))

(deftest float32-face-identity-matches-the-jvm
  (let [vertices [[0 0 0] [1 0 0] [0 1 0]]
        expected "0000000000000000000000003f8000000000000000000000000000003f80000000000000"]
    (is (= expected (faces/face-key vertices)))
    (is (not= expected (faces/face-key (reverse vertices))))
    (is (= expected (faces/face-key [[0 1 0] [0 0 0] [1 0 0]])))
    (is (= expected (faces/face-key [[0 -0.0 0] [1 0 0] [0 1 0]])))))

(deftest cross-instance-id-ranges
  (let [pixels (js/Uint8Array. #js [1 0 0 255 2 0 0 255 4 0 0 255 0 0 0 255])
        buffer {:pixels pixels :width 4 :height 1
                :ranges {[] {:start 1 :end 3} [[:weapon 0]] {:start 3 :end 6}}}]
    (is (= {[] #{0 1} [[:weapon 0]] #{1}} (brush/sampled-instances buffer 2 0.5 10 [] true)))
    (is (= {[] #{0 1}} (brush/sampled-instances buffer 2 0.5 10 [] false)))
    (is (= {} (brush/sampled-instances buffer 20 0.5 1 [] true)))))
