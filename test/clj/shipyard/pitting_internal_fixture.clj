(ns shipyard.pitting-internal-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as f]))

(def id "Fleet/Cruiser/Joined hull")

(defn build! [root]
  (let [source (fs/file root id "unsupported.stl")]
    (fs/create-dirs (.getParentFile source))
    (with-open [out (io/output-stream source)]
      (.write out ^bytes (f/->binary-stl (f/face-touching-cubes 2.0)))))
  root)
