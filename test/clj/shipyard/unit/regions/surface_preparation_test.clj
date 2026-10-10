(ns shipyard.unit.regions.surface-preparation-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.regions.surface-preparation :as preparation]
            [shipyard.regions.surfaces :as surfaces])
  (:import [java.nio ByteBuffer ByteOrder]))

(deftest mesh-triangles-test
  (testing "Tier-zero ordinals preserve index order and coordinate seams"
    (is (= [[[0 0 0] [0 1 0] [1 0 0]]]
           (preparation/mesh-triangles {:positions [0 0 0 1 0 0 0 1 0] :indices [0 2 1]})))))

(deftest encode-components-test
  (testing "Little endian component buffers have linear ids, offsets, and members"
    (let [encoded (preparation/encode-components (surfaces/partition-components (surfaces/topology [[[0 0 0] [1 0 0] [0 1 0]]]) 1))
          buffer (.order (ByteBuffer/wrap encoded) ByteOrder/LITTLE_ENDIAN)]
      (is (= [1 1 0 0 1 0] (vec (repeatedly 6 #(.getInt buffer)))))
      (is (= 12 (alength (preparation/encode-components {:ids [] :offsets [0] :members []})))))))
