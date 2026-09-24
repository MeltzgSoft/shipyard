(ns shipyard.regions.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.region-fixture :as fixture]
            [shipyard.regions.transport :as transport]))

(deftest selection-indices
  (testing "uint32 and bitset represent the same ordinals"
    (is (= [0 7 8] (transport/selection-indices 9 "indices" (fixture/uint32-bytes [0 7 8]))))
    (is (= [0 7 8] (transport/selection-indices 9 "bitset" (byte-array [(unchecked-byte 129) 1])))))
  (testing "invalid index bounds, widths, masks and padding never truncate silently"
    (doseq [[count encoding payload] [[0 "bitset" (byte-array [])]
                                      [9 "indices" (fixture/uint32-bytes [9])]
                                      [9 "indices" (fixture/uint32-bytes [4294967295])]
                                      [9 "indices" (byte-array [0])]
                                      [9 "bitset" (byte-array [1])]
                                      [9 "bitset" (byte-array [0 2])]
                                      [9 "unknown" (byte-array [0 0])]]]
      (is (thrown? clojure.lang.ExceptionInfo (transport/selection-indices count encoding payload))))))

(deftest decode
  (let [metadata {:part-id "船" :revision "9007199254740993"}
        body (fixture/cbor-stroke metadata 1000 "indices" (fixture/uint32-bytes [3 8]))]
    (is (= {:version 1 :metadata {"part-id" "船" "revision" "9007199254740993"}
            :triangle-count 1000 :encoding "indices" :indices [3 8]}
           (transport/decode body)))
    (is (thrown? Exception (transport/decode (byte-array (concat body [0])))))
    (is (thrown? Exception (transport/decode (byte-array (drop-last body))))))
  (is (thrown? Exception (transport/decode (fixture/cbor-stroke {} 10 "indices" (fixture/uint32-bytes [0]) {:tag 66}))))
  (is (thrown? Exception (transport/decode (fixture/cbor-stroke {:faces "ignored?"} 10 "bitset" (byte-array [1 0]))))))
