(ns shipyard.paint.projection-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [shipyard.paint.projection :as projection]))

(def metadata {:mesh-key "source" :region-revision "9007199254740993"
               :layer-table ["Primary" "Secondary"]
               :detail-table [nil {:base [1 0 0] :metalness 0 :roughness 1 :glow 1}]})

(deftest projection-roundtrip-test
  (testing "zero-copy numeric masks and exact decimal revision survive both runtimes"
    (let [value (projection/decode (projection/encode metadata [0 1 1] [0 1 0]))]
      (is (= metadata (select-keys value (keys metadata))))
      (is (= [0 1 1] (vec (:triangle-layers value))))
      (is (= [0 1 0] (vec (:triangle-details value))))))
  (testing "empty sources"
    (is (zero? (:triangle-count (projection/decode (projection/encode metadata [] [])))))))

(deftest projection-validation-test
  (testing "malformed masks are rejected before rendering"
    (is (thrown? #?(:clj Exception :cljs js/Error) (projection/encode metadata [0] [])))
    (is (thrown? #?(:clj Exception :cljs js/Error) (projection/decode (projection/encode metadata [2] [0]))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (projection/decode (projection/encode metadata [0] [2]))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (projection/decode (projection/encode metadata [-1] [0]))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (projection/decode (projection/encode (assoc metadata :detail-table []) [] []))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (projection/decode #?(:clj (byte-array 2) :cljs (js/ArrayBuffer. 2)))))))
