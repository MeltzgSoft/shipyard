(ns shipyard.paint.topology-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.topology :as topology])
  (:import [java.nio ByteBuffer ByteOrder]))

(def mesh {:positions (float-array [0 0 0 1 0 0 0 1 0])
           :normals (float-array [0 0 1 0 0 1 0 0 1])
           :indices (int-array [0 1 2 2 0 1])})

(deftest triangle-points-test
  (testing "source triangle winding and rotations are preserved"
    (is (= [[0.0 1.0 0.0] [0.0 0.0 0.0] [1.0 0.0 0.0]] (topology/triangle-points mesh 1)))))

(deftest encode-test
  (testing "nonindexed source buffers, source identities and duplicate ordinals agree"
    (let [bytes (topology/encode mesh)
          buffer (doto (ByteBuffer/wrap bytes) (.order ByteOrder/LITTLE_ENDIAN))
          key (faces/face-key (topology/triangle-points mesh 0))]
      (is (= 232 (alength bytes)))
      (is (= 1 (.getInt buffer)))
      (is (= 2 (.getInt buffer)))
      (is (= (vec (mapcat identity (topology/triangle-points mesh 0))) (vec (repeatedly 9 #(.getFloat buffer)))))
      (.position buffer (+ 8 (* 2 72)))
      (is (= key (apply str (repeatedly 9 #(format "%08x" (.getInt buffer))))))
      (.position buffer (+ 8 (* 2 108)))
      (is (= [0 1] (vec (repeatedly 2 #(.getInt buffer)))))))
  (testing "empty topology is a valid bounded resource"
    (is (= 8 (alength (topology/encode {:positions [] :indices []}))))))

(deftest triangle-normal-test
  (testing "missing wire normals are prepared by workers with source winding"
    (is (= [0.0 0.0 1.0] (topology/triangle-normal [[0 0 0] [1 0 0] [0 1 0]])))
    (is (= [0.0 0.0 -1.0] (topology/triangle-normal [[0 0 0] [0 1 0] [1 0 0]])))
    (is (= [0.0 0.0 0.0] (topology/triangle-normal [[0 0 0] [0 0 0] [0 0 0]])))
    (let [bytes (topology/encode (dissoc mesh :normals)) buffer (doto (ByteBuffer/wrap bytes) (.order ByteOrder/LITTLE_ENDIAN))]
      (.position buffer (+ 8 (* 2 36)))
      (is (= [0.0 0.0 1.0] (vec (repeatedly 3 #(.getFloat buffer))))))))
