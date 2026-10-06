(ns shipyard.scheme.material-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.scheme.material :as m]))

(deftest srgb->linear-test
  (testing "sRGB transfer curve endpoints and middle"
    (is (= 0.0 (m/srgb->linear 0)))
    (is (= 1.0 (m/srgb->linear 1)))
    (is (< (#?(:clj Math/abs :cljs js/Math.abs) (- 0.21404114 (m/srgb->linear 0.5))) 1.0e-7))))

(deftest palette-projection-and-primary-fallback
  (let [primary (assoc m/neutral :metalness 1)
        palette {:scheme/layers {"Primary" primary}}
        ship {:scheme/base palette :scheme/details {}}]
    (is (= primary (m/resolve-material palette)))
    (is (= primary (m/resolve-material ship)))
    (is (= m/neutral (m/resolve-material {:scheme/layers {"Secondary" primary}})))
    (is (= m/neutral (m/resolve-material {:scheme/base nil})))
    (is (= (:scheme/layers palette) (:scheme/layers (m/effective-profile ship))))))
