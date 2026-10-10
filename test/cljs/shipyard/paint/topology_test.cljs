(ns shipyard.paint.topology-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [shipyard.paint.topology :as topology]
            [shipyard.paint.faces :as faces]))

(defn fixture [n]
  (let [buffer (js/ArrayBuffer. (+ 8 (* n 112))) header (js/Uint32Array. buffer 0 2)
        positions (js/Float32Array. buffer 8 (* n 9))
        words (js/Uint32Array. buffer (+ 8 (* n 72)) (* n 9))
        sorted (js/Uint32Array. buffer (+ 8 (* n 108)) n)
        points [[0 0 0] [1 0 0] [0 1 0]] key (faces/face-key points)]
    (aset header 0 1) (aset header 1 n)
    (dotimes [triangle n]
      (.set positions (clj->js (mapcat identity points)) (* triangle 9))
      (dotimes [word 9] (aset words (+ (* triangle 9) word) (js/parseInt (subs key (* word 8) (* (inc word) 8)) 16)))
      (aset sorted triangle triangle))
    buffer))

(deftest zero-copy-identity-and-lookup
  (testing "backend word identity preserves duplicates with logarithmic lookup"
    (let [buffer (fixture 3) prepared (topology/decode buffer)
          key (faces/face-key [[0 0 0] [1 0 0] [0 1 0]])]
      (is (identical? buffer (.-buffer (.-positions prepared))))
      (is (= key (topology/face-key prepared 1)))
      (is (= [0 1 2] (topology/triangles prepared key)))
      (is (= [] (topology/triangles prepared (apply str (repeat 72 "f")))))))
  (testing "corrupt resources fail before creating geometry"
    (is (thrown? js/Error (topology/decode (js/ArrayBuffer. 8))))))
