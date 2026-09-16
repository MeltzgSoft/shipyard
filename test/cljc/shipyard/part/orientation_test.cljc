(ns shipyard.part.orientation-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.part.orientation :as orientation]))

(defn- close? [a b]
  (< (#?(:clj Math/abs :cljs js/Math.abs) (- (double a) (double b))) 1.0e-6))

(defn- vector-close? [a b]
  (every? true? (map close? a b)))

(deftest normalize-quaternion-test
  (testing "normalizes finite quaternion components"
    (is (vector-close? [0.0 0.0 0.0 1.0]
                       (orientation/normalize-quaternion [0 0 0 2]))))
  (testing "rejects malformed and zero-length values"
    (is (nil? (orientation/normalize-quaternion nil)))
    (is (nil? (orientation/normalize-quaternion [0 0 0])))
    (is (nil? (orientation/normalize-quaternion [0 0 0 0])))
    (doseq [q [[1 0 "bad" 1] [nil 0 0 1]
               [#?(:clj Double/NaN :cljs js/NaN) 0 0 1]
               [#?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity) 0 0 1]]]
      (is (nil? (orientation/normalize-quaternion q)))))
  (testing "large finite rotations cannot overflow to a successful zero quaternion"
    (doseq [q [[1.0e308 0.0 0.0 1.0e308] [1.0e200 1.0e200 1.0e200 1.0e200]
               [9223372036854775807.0 0.0 0.0 9223372036854775807.0]]]
      (let [normalized (orientation/normalize-quaternion q)]
        (is (= 4 (count normalized)))
        (is (every? #(#?(:clj Double/isFinite :cljs js/Number.isFinite) (double %)) normalized))
        (is (close? 1.0 (reduce + (map #(* % %) normalized))))))))

(deftest orientation-of-test
  (testing "defaults missing and invalid metadata to identity"
    (is (= orientation/identity-quaternion (orientation/orientation-of nil)))
    (is (= orientation/identity-quaternion (orientation/orientation-of [0 0 0 0]))))
  (testing "normalizes persisted metadata"
    (is (vector-close? [0.0 0.0 0.0 1.0]
                       (orientation/orientation-of [0 0 0 2])))))

(deftest quaternion-multiply-test
  (testing "identity leaves a quaternion unchanged"
    (let [q (orientation/from-euler-degrees 90 0 0)]
      (is (vector-close? q (orientation/quaternion-multiply orientation/identity-quaternion q)))
      (is (vector-close? q (orientation/quaternion-multiply q orientation/identity-quaternion))))))

(deftest from-euler-degrees-test
  (testing "uses yaw around Y, pitch around X and roll around Z"
    (is (vector-close? [1.0 0.0 0.0]
                       (orientation/rotate-vector
                        (orientation/from-euler-degrees 90 0 0)
                        [0.0 0.0 1.0])))
    (is (vector-close? [0.0 0.0 -1.0]
                       (orientation/rotate-vector
                        (orientation/from-euler-degrees 0 90 0)
                        [0.0 -1.0 0.0])))
    (is (vector-close? [0.0 1.0 0.0]
                       (orientation/rotate-vector
                        (orientation/from-euler-degrees 0 0 90)
                        [1.0 0.0 0.0]))))
  (testing "keeps roll on the canonical Z axis after yaw"
    (is (vector-close? [0.0 -1.0 0.0]
                       (orientation/rotate-vector
                        (orientation/from-euler-degrees -90 0 90)
                        [0.0 0.0 1.0])))))

(deftest rotate-around-world-axis-test
  (testing "each edit uses canonical space even after another axis changed"
    (let [after-roll (orientation/rotate-around-world-axis
                      orientation/identity-quaternion :z 90)
          after-yaw (orientation/rotate-around-world-axis after-roll :y -90)]
      (is (vector-close?
           (orientation/quaternion-multiply
            (orientation/from-euler-degrees -90 0 0)
            (orientation/from-euler-degrees 0 0 90))
           after-yaw)))))

(deftest to-euler-degrees-test
  (testing "round-trips ordinary ZXY angles"
    (let [angles [45.0 -30.0 20.0]]
      (is (vector-close? angles
                         (orientation/to-euler-degrees
                          (apply orientation/from-euler-degrees angles))))))
  (testing "returns zeroes for identity"
    (is (vector-close? [0.0 0.0 0.0]
                       (orientation/to-euler-degrees orientation/identity-quaternion)))))

(deftest inverse-test
  (testing "undoes an orientation"
    (let [q (orientation/from-euler-degrees 45 30 -15)
          point [2.0 3.0 4.0]]
      (is (vector-close? point
                         (orientation/rotate-vector
                          (orientation/inverse q)
                          (orientation/rotate-vector q point)))))))

(deftest relative-orientation-test
  (testing "returns identity when the candidate is the saved orientation"
    (let [saved (orientation/from-euler-degrees 45 30 -15)]
      (is (vector-close? orientation/identity-quaternion
                         (orientation/relative-orientation saved saved)))))
  (testing "returns the rotation that advances the saved orientation to the candidate"
    (let [saved (orientation/from-euler-degrees 35 -20 10)
          delta (orientation/from-euler-degrees -15 25 40)
          candidate (orientation/quaternion-multiply delta saved)]
      (is (vector-close? delta
                         (orientation/relative-orientation saved candidate))))))

(deftest rotate-vector-test
  (testing "identity leaves vectors unchanged"
    (is (vector-close? [1.0 2.0 3.0]
                       (orientation/rotate-vector orientation/identity-quaternion
                                                  [1.0 2.0 3.0])))))

(deftest oriented-bounds-test
  (testing "rotates all eight corners before finding new axis-aligned bounds"
    (let [[minimum maximum]
          (orientation/oriented-bounds [-1.0 -2.0 -3.0]
                                       [1.0 2.0 3.0]
                                       (orientation/from-euler-degrees 90 0 0))]
      (is (vector-close? [-3.0 -2.0 -1.0] minimum))
      (is (vector-close? [3.0 2.0 1.0] maximum)))))

(deftest canonical-mount-roll-test
  (testing "keeps mount +Y aligned with canonical part up"
    (is (vector-close? [1.0 0.0 0.0]
                       (orientation/canonical-mount-roll orientation/identity-quaternion
                                                         [0.0 0.0 1.0]))))
  (testing "uses canonical forward for a horizontal mounting face"
    (is (vector-close? [-1.0 0.0 0.0]
                       (orientation/canonical-mount-roll orientation/identity-quaternion
                                                         [0.0 1.0 0.0]))))
  (testing "accounts for source-to-canonical part orientation"
    (let [q (orientation/from-euler-degrees 0 0 90)]
      (is (vector-close? [0.0 -1.0 0.0]
                         (orientation/canonical-mount-roll q [0.0 0.0 1.0]))))))

(deftest orient-mount-frame-test
  (testing "replaces the facet edge roll with the canonical roll"
    (is (vector-close? [1.0 0.0 0.0]
                       (:mount/roll
                        (orientation/orient-mount-frame
                         {:mount/axis [0.0 0.0 1.0]
                          :mount/roll [0.0 1.0 0.0]}
                         orientation/identity-quaternion))))))

(deftest reflect-position-test
  (testing "reflects across canonical planes in source space"
    (is (vector-close? [-1.0 2.0 3.0]
                       (orientation/reflect-position orientation/identity-quaternion
                                                     :x 0.0 [1.0 2.0 3.0])))
    (is (vector-close? [1.0 2.0 3.0]
                       (orientation/reflect-position
                        (orientation/from-euler-degrees 90 0 0)
                        :z 0.0 [-1.0 2.0 3.0])))))

(deftest reflect-direction-test
  (testing "reflects directions without applying plane offsets"
    (is (vector-close? [1.0 -2.0 3.0]
                       (orientation/reflect-direction orientation/identity-quaternion
                                                      :y [1.0 2.0 3.0])))))

(deftest plane-distance-test
  (testing "measures distance along a canonical axis"
    (is (close? 2.0
                (orientation/plane-distance orientation/identity-quaternion
                                            :z 1.0 [0.0 0.0 3.0])))))

(deftest save-request-test
  (testing "preserves the browser's world-space quaternion exactly"
    (is (vector-close? [-0.5 -0.5 0.5 0.5]
                       (:orientation
                        (orientation/save-request
                         {"action" "save"
                          "part-orientation-mode" "world"
                          "part-orientation-quaternion" "-0.5,-0.5,0.5,0.5"})))))
  (testing "parses finite absolute Euler angles into a quaternion"
    (is (vector-close? (orientation/from-euler-degrees 90 0 -90)
                       (:orientation
                        (orientation/save-request {"action" "save"
                                                   "part-yaw-deg" "90"
                                                   "part-pitch-deg" "0"
                                                   "part-roll-deg" "-90"})))))
  (testing "resets to identity"
    (is (= {:orientation orientation/identity-quaternion}
           (orientation/save-request {"action" "reset"}))))
  (testing "treats blank angle fields as zero"
    (is (vector-close? (orientation/from-euler-degrees 90 0 0)
                       (:orientation
                        (orientation/save-request {"action" "save"
                                                   "part-yaw-deg" "90"
                                                   "part-pitch-deg" ""
                                                   "part-roll-deg" " "})))))
  (testing "rejects missing, non-finite and unknown requests"
    (is (:error (orientation/save-request {"action" "save"})))
    (is (:error (orientation/save-request {"action" "save"
                                           "part-yaw-deg" "NaN"
                                           "part-pitch-deg" "0"
                                           "part-roll-deg" "0"})))
    (is (:error (orientation/save-request {"action" "rotate"})))))
