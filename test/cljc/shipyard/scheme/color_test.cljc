(ns shipyard.scheme.color-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [shipyard.scheme.color :as color]))

(deftest valid-hex
  (doseq [hex ["#000000" "#d4AF37" "#ffffff"]] (is (color/valid-hex? hex)))
  (doseq [hex [nil "red" "#fff" "#gg0000" "000000"]] (is (not (color/valid-hex? hex)))))

(deftest conversions
  (doseq [hex ["#000000" "#ffffff" "#ff0000" "#00ff00" "#0000ff" "#d4af37" "#808080" "#3498ab"]]
    (is (= hex (color/hsv->hex (color/hex->hsv hex)))))
  (is (= "#ff0000" (color/hsv->hex [360 1 1])))
  (is (= "#800000" (color/hsv->hex [0 1 0.5]))))

(deftest picker-coordinates-survive-lossy-rgb-conversion
  (doseq [[hex hsv] [["#808080" [210 0 (/ 128.0 255)]]
                     ["#000000" [210 0.7 0]]
                     ["#FFFFFF" [280 0 1]]
                     [(color/hsv->hex [213 0.004 0.01]) [213 0.004 0.01]]]]
    (is (= hsv (color/picker-value hex hsv))))
  (doseq [candidate [nil [] [210 0] [nil 0 0] ["210" 0 0]
                     [-1 0 0] [360 0 0] [210 -0.1 0] [210 1.1 0]
                     [210 0 -0.1] [210 0 1.1] [210 1 1]]]
    (is (= (color/hex->hsv "#000000") (color/picker-value "#000000" candidate))
        "Malformed or mismatched coordinates cannot replace the authoritative color")))
