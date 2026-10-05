(ns shipyard.picked-face-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as fixture]))

(def id "Fleet/Cruiser/Dense Mount Plate")
(def other-id "Fleet/Cruiser/Other Mount Plate")
(def dense-point [-15.23 0.27 0])
(def small-point [15.23 0.27 0])
(def dense-triangles
  (vec (mapcat (fn [[x y]]
                 [[[x y 0] [(inc x) y 0] [x (inc y) 0]]
                  [[(inc x) y 0] [(inc x) (inc y) 0] [x (inc y) 0]]])
               (for [x (range -25 -5) y (range -10 10)] [x y]))))
(def small-triangles [[[5 -10 0] [25 -10 0] [5 10 0]]
                      [[25 -10 0] [25 10 0] [5 10 0]]])

(defn build! [root]
  (doseq [[part triangles] [[id (into dense-triangles small-triangles)] [other-id (fixture/cube 1)]]]
    (let [file (io/file (str root) part "unsupported.stl")]
      (fs/create-dirs (.getParentFile file))
      (with-open [out (io/output-stream file)]
        (.write out ^bytes (fixture/->binary-stl triangles)))))
  root)
