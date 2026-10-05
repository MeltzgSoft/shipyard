(ns shipyard.variant-availability-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as meshes]))

(def all "Fleet/Cruiser/All versions")
(def plain "Fleet/Cruiser/Plain")
(def cut "Fleet/Cruiser/Cut")
(def supported "Fleet/Cruiser/Supported Only")

(defn extra-id [n] (format "Fleet/Cruiser/Plain %03d" n))

(defn build!
  ([root] (build! root 0))
  ([root extra-count]
   (let [parts (concat [[all [:unsupported :supported :unsupported-pitted]]
                        [plain [:unsupported]]
                        [cut [:unsupported :unsupported-pitted]]
                        [supported [:supported]]]
                       (for [n (range extra-count)] [(extra-id n) [:unsupported]]))
         content (meshes/->binary-stl (meshes/cube 1))]
     (doseq [[id variants] parts variant variants]
       (let [file (fs/file root id (str (name variant) ".stl"))]
         (fs/create-dirs (.getParentFile file))
         (with-open [out (io/output-stream file)] (.write out ^bytes content)))))
   root))
