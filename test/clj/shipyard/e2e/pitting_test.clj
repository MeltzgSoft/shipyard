(ns shipyard.e2e.pitting-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.fixtures :as f]
            [shipyard.mesh.stl :as stl]
            [shipyard.pitting.geometry :as geometry])
  (:import [java.nio.file Files]
           [java.util Arrays]))

(deftest browse-cut-results-from-edge-touching-surfaces
  (s/assert-bundle!)
  (let [source (f/->binary-stl (f/edge-touching-cubes 2.0))
        frame {:mount/pos [0.0 0.0 1.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]}
        results (into {"Source" (geometry/mesh-triangles (stl/parse-bytes source))}
                      (for [[name cut] [["Pit" {:kind :pit :depth 0.5 :diameter 0.5}]
                                        ["Recess" {:kind :recess :depth 0.5 :border 0.25}]]]
                        [name (geometry/subtract (stl/parse-bytes source)
                                                 [(assoc frame :mount/cut cut
                                                         :mount/outline [[[-1 -1 1] [1 -1 1] [1 1 1] [-1 1 1]]])])]))
        build! (fn [root]
                 (doseq [[name triangles] results]
                   (let [file (io/file (str root) "Fleet" "Cruiser" name "unsupported.stl")]
                     (fs/create-dirs (.getParentFile file))
                     (with-open [out (io/output-stream file)]
                       (.write out ^bytes (if (= name "Source") source (geometry/binary-stl triangles))))))
                 root)
        started (fixture/start! true build! (fn [_])) sys (:system started) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (doseq [name ["Source" "Pit" "Recess"]]
        (s/open-prepared-part! driver sys name (str "Fleet/Cruiser/" name))
        (is (= (count (get results name)) (:triangles (s/stats driver))))
        (is (not= "failed" (:status (s/stats driver)))))
      (is (Arrays/equals ^bytes source ^bytes (Files/readAllBytes
                                               (.toPath (io/file (str (:root started)) "Fleet/Cruiser/Source/unsupported.stl")))))
      (s/screenshot-el! driver "#viewport" (io/file "/tmp/shipyard-shared-edge-recess.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))
