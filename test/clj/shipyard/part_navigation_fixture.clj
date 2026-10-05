(ns shipyard.part-navigation-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as mesh]))

(defn id [n] (format "Fleet/Cruiser/Filtered %02d" n))
(defn build! [root]
  (let [bytes (mesh/->binary-stl (mesh/cube 2))]
    (doseq [[id variant] (concat (map (fn [n] [(id n) "unsupported"]) (range 65))
                                 [["Fleet/Cruiser/Unrelated" "unsupported"] ["Fleet/Cruiser/Support Only" "supported"]])]
      (let [file (fs/file root id (str variant ".stl"))]
        (fs/create-dirs (.getParentFile file))
        (with-open [out (io/output-stream file)] (.write out ^bytes bytes)))))
  root)
