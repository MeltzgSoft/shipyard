(ns shipyard.regions.transport-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["cbor-x" :refer [decode]]
            [shipyard.regions.transport :as transport]))

(deftest selection
  (testing "sparse lists use RFC 8746 uint32 arrays"
    (let [decoded (decode (transport/encode #js {"revision" "9007199254740993"} 1000 [0 127 999]))]
      (is (= "indices" (aget decoded 3)))
      (is (instance? js/Uint32Array (aget decoded 4)))
      (is (= [0 127 999] (vec (array-seq (aget decoded 4)))))
      (is (= "9007199254740993" (aget (aget decoded 1) "revision")))))
  (testing "dense masks use LSB-first bytes including the last partial byte"
    (let [decoded (decode (transport/encode #js {} 9 [0 7 8]))]
      (is (= "bitset" (aget decoded 3)))
      (is (= [129 1] (vec (array-seq (aget decoded 4)))))))
  (testing "ties retain indices"
    (is (= "indices" (aget (transport/selection 32 [1]) 0)))))
