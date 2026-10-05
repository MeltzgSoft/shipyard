(ns shipyard.part-variants-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as meshes]))

(def a "Fleet/Cruiser/Battery")
(def b "Fleet/Cruiser/Battery Supported")
(def c "Fleet/Cruiser/Other Battery")

(defn build! [root]
  (doseq [[id variant size] [[a "unsupported" 1] [b "supported" 2] [c "unsupported" 3]]]
    (let [file (fs/file root id (str variant ".stl"))]
      (fs/create-dirs (.getParentFile file))
      (with-open [out (io/output-stream file)] (.write out ^bytes (meshes/->binary-stl (meshes/cube size))))))
  root)

