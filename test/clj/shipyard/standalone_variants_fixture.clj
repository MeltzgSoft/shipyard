(ns shipyard.standalone-variants-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as meshes]
            [shipyard.variant-availability-fixture :as variants]))

(def pitted "Fleet/Cruiser/Pitted Only")
(def supported-pitted "Fleet/Cruiser/Supported And Pitted")

(defn build!
  ([root] (build! root 0))
  ([root extra-count]
   (variants/build! root extra-count)
   (doseq [[id types] [[pitted [:unsupported-pitted]] [supported-pitted [:supported :unsupported-pitted]]]
           variant types]
     (let [file (fs/file root id (str (name variant) ".stl"))]
       (fs/create-dirs (.getParentFile file))
       (with-open [out (io/output-stream file)]
         (.write out ^bytes (meshes/->binary-stl (meshes/cube 1))))))
   root))
