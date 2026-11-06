(ns shipyard.mount-boundary-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as fixture]))

(def thin-id "Fleet/Cruiser/turrets/Thin turret")
(def duplicate-id "Fleet/Cruiser/turrets/Duplicated turret")

(def triangles
  ;; A vertex 0.004 mm from an already-connected outer edge is intentional
  ;; triangulation. Inserting it into that edge breaks internal cancellation.
  (let [a [0 0 0] b [4 0 0] c [4 2 0] d [0 2 0] m [2 0.004 0]]
    [[a b m] [b c m] [c d m] [d a m]]))

(def duplicate-triangles (into triangles triangles))

(defn build! [root]
  (doseq [[id tris] [[thin-id triangles] [duplicate-id duplicate-triangles]]]
    (let [file (io/file (str root) id "unsupported.stl")]
      (fs/create-dirs (.getParentFile file))
      (with-open [out (io/output-stream file)]
        (.write out ^bytes (fixture/->binary-stl tris)))))
  root)
