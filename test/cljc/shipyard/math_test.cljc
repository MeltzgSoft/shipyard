(ns shipyard.math-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.math :as math]))

(defn- close? [a b]
  (< (#?(:clj Math/abs :cljs js/Math.abs) (- a b)) 1.0e-9))

(deftest finite-number?-test
  (testing "accepts finite numbers"
    (is (math/finite-number? 1.5)))
  (testing "rejects non-numbers and non-finite values"
    (is (not (math/finite-number? nil)))
    (is (not (math/finite-number? #?(:clj Double/POSITIVE_INFINITY
                                     :cljs js/Number.POSITIVE_INFINITY))))))

(deftest parse-finite-double-test
  (testing "parses finite numbers"
    (is (= 1.5 (math/parse-finite-double "1.5"))))
  (testing "rejects invalid and non-finite values"
    (is (nil? (math/parse-finite-double "not-a-number")))
    (is (nil? (math/parse-finite-double "Infinity")))))

(deftest add-test
  (testing "adds corresponding components"
    (is (= [5 7 9] (math/add [1 2 3] [4 5 6]))))
  (testing "supports empty vectors"
    (is (= [] (math/add [] [])))))

(deftest subtract-test
  (testing "subtracts corresponding components"
    (is (= [-3 -3 -3] (math/subtract [1 2 3] [4 5 6]))))
  (testing "supports empty vectors"
    (is (= [] (math/subtract [] [])))))

(deftest scale-test
  (testing "scales every component"
    (is (= [2 4 6] (math/scale 2 [1 2 3]))))
  (testing "supports empty vectors"
    (is (= [] (math/scale 2 [])))))

(deftest dot-test
  (testing "computes the dot product"
    (is (= 32 (math/dot [1 2 3] [4 5 6]))))
  (testing "returns zero for empty vectors"
    (is (zero? (math/dot [] [])))))

(deftest cross-test
  (testing "computes the three-dimensional cross product"
    (is (= [0 0 1] (math/cross [1 0 0] [0 1 0]))))
  (testing "parallel vectors produce the zero vector"
    (is (= [0 0 0] (math/cross [1 0 0] [2 0 0])))))

(deftest length-test
  (testing "computes vector magnitude"
    (is (= 5.0 (math/length [3 4]))))
  (testing "the empty vector has zero magnitude"
    (is (= 0.0 (math/length [])))))

(deftest normalize-test
  (testing "returns a unit vector"
    (let [[x y] (math/normalize [3.0 4.0])]
      (is (close? 0.6 x))
      (is (close? 0.8 y))))
  (testing "rejects zero, malformed, and threshold-length vectors"
    (is (nil? (math/normalize [0.0 0.0])))
    (is (nil? (math/normalize nil)))
    (is (nil? (math/normalize [1.0e-12 0.0] 1.0e-12)))))

(deftest project-onto-plane-test
  (testing "removes the component along the plane normal"
    (is (= [1.0 2.0 0.0]
           (math/project-onto-plane [0.0 0.0 1.0] [1.0 2.0 3.0]))))
  (testing "leaves an in-plane vector unchanged"
    (is (= [1.0 2.0 0.0]
           (math/project-onto-plane [0.0 0.0 1.0] [1.0 2.0 0.0])))))
