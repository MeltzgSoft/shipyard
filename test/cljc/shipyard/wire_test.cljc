(ns shipyard.wire-test
  "Runs on BOTH runtimes - JVM via kaocha, CLJS via shadow-cljs :node-test.

  That is the point of the namespace. A JVM-only pass proves the encoder agrees
  with itself; only running the same roundtrip in the browser runtime proves the
  encoder and the decoder agree on the binary layout, which is the failure this
  format's single-source-of-truth design exists to prevent."
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.wire :as wire]))

(defn- farr [xs]
  #?(:clj (float-array xs) :cljs (js/Float32Array. (clj->js xs))))

(defn- iarr [xs]
  #?(:clj (int-array xs) :cljs (js/Uint32Array. (clj->js xs))))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-5))

(def ^:private sample
  {:positions    (farr [0 0 0, 1 0 0, 0 1 0, 1 1 0])
   :normals      (farr [0 0 1, 0 0 1, 0 0 1, 0 0 1])
   :indices      (iarr [0 1 2, 1 3 2])
   :vertex-count 4
   :bbox-min     [0.0 0.0 0.0]
   :bbox-max     [1.0 1.0 0.0]})

(deftest roundtrip
  (let [d (wire/decode (wire/encode sample))]
    (is (= 4 (:vertex-count d)))
    (is (= 6 (:index-count d)))
    (testing "positions survive exactly"
      (dotimes [i 12]
        (is (close? (aget (:positions sample) i) (aget (:positions d) i)))))
    (testing "normals survive exactly"
      (dotimes [i 12]
        (is (close? (aget (:normals sample) i) (aget (:normals d) i)))))
    (testing "indices survive exactly"
      (dotimes [i 6]
        (is (= (aget (:indices sample) i) (aget (:indices d) i)))))))

(deftest bbox-comes-from-the-header
  (testing "so a camera can frame a part without touching the vertex data"
    (let [d (wire/decode (wire/encode sample))]
      (is (every? true? (map close? [0.0 0.0 0.0] (:bbox-min d))))
      (is (every? true? (map close? [1.0 1.0 0.0] (:bbox-max d)))))))

(deftest normals-are-optional
  (let [d (wire/decode (wire/encode (dissoc sample :normals)))]
    (is (nil? (:normals d)))
    (is (= 4 (:vertex-count d)))
    (is (= 6 (:index-count d)))
    (testing "indices still land at the right offset with no normal block"
      (dotimes [i 6]
        (is (= (aget (:indices sample) i) (aget (:indices d) i)))))))

(deftest size-is-exactly-what-the-layout-says
  (let [encoded (wire/encode sample)
        n #?(:clj (alength ^bytes encoded) :cljs (.-byteLength encoded))]
    (is (= (wire/byte-size 4 6 true) n))
    (is (= (+ 48 (* 12 4) (* 12 4) (* 6 4)) n))))

(deftest bad-magic-is-rejected
  (let [encoded (wire/encode sample)]
    #?(:clj  (aset ^bytes encoded 0 (byte 0))
       :cljs (.setUint8 (js/DataView. encoded) 0 0))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (wire/decode encoded)))))

(deftest unknown-version-is-rejected
  (let [encoded (wire/encode sample)]
    #?(:clj  (.putInt (doto (java.nio.ByteBuffer/wrap ^bytes encoded)
                        (.order java.nio.ByteOrder/LITTLE_ENDIAN))
                      (int wire/off-version) (int 99))
       :cljs (.setUint32 (js/DataView. encoded) wire/off-version 99 true))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (wire/decode encoded)))))

(deftest layout-constants-are-consistent
  (testing "offsets are declared once and derived, never repeated as literals"
    (is (= 48 wire/off-payload))
    (is (= (+ wire/off-payload (* 3 10 wire/float-bytes) (* 3 10 wire/float-bytes))
           (wire/indices-offset 10 true)))
    (is (= (+ wire/off-payload (* 3 10 wire/float-bytes))
           (wire/indices-offset 10 false)))))
