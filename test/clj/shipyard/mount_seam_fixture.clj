(ns shipyard.mount-seam-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as fixture]))

(def id "Fleet/Grand Cruiser/Mirrored Hull")
(def left-point [-2.1 0 -3.7])
(def right-point [2.1 0 -3.7])
(def island-point [10.1 0 0.3])
(def left-triangles
  [[[-5 0 4.2] [-2.5 0 4.2] [-5 0 -12.5]]
   [[-5 0 -12.5] [-2.5 0 4.2] [0 0 -12.5]]
   [[0 0 -12.5] [-2.5 0 4.2] [0 0 12.5]]
   [[0 0 12.5] [-2.5 0 4.2] [-2.5 0 12.5]]])
(def face-triangles
  (into left-triangles
        (map (fn [t] (mapv (fn [[x y z]] [(- x) y z]) (reverse t))) left-triangles)))
(def internal-triangles
  [[[0 0 -12.5] [0 0 12.5] [0 -5 -12.5]]
   [[0 0 12.5] [0 0 -12.5] [0 -5 -12.5]]])
(def island-triangles
  [[[8 0 -2] [8 0 2] [12 0 -2]]
   [[12 0 -2] [8 0 2] [12 0 2]]])
(def triangles (vec (concat face-triangles internal-triangles island-triangles)))

(defn build! [root]
  (let [file (io/file (str root) id "unsupported.stl")]
    (fs/create-dirs (.getParentFile file))
    (with-open [out (io/output-stream file)]
      (.write out ^bytes (fixture/->binary-stl triangles))))
  root)
