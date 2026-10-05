(ns shipyard.integration.pitting-internal-faces-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.stl :as stl]
            [shipyard.mesh.cache :as cache]
            [shipyard.pitting.db :as pitting]
            [shipyard.pitting.geometry :as geometry]
            [shipyard.pitting-internal-fixture :as source])
  (:import [java.nio.file Files]
           [java.util Arrays]))

(deftest internal-face-cuts-persist-and-failed-regeneration-keeps-prior-output
  (let [started (fixture/start! false source/build! (fn [_])) sys (:system started)
        cat (:shipyard.catalog/db sys) lib (:shipyard.library/index sys)
        deps {:catalog cat :library lib}
        file (fs/file (:root started) source/id "unsupported.stl")
        target (pitting/target-file file)
        original (Files/readAllBytes (.toPath file))
        prepared (cache/ensure! (:shipyard.mesh/cache sys) file)
        key (index/record-mesh-key! lib source/id (:mesh-key prepared) (:tris prepared))
        part #(:part (catalog/part-context! cat source/id))
        mount {:mount/id :top :mount/kind :socket :mount/accepts #{:weapon} :mount/capacity 1
               :mount/origin :picked :mount/pos [1.0 0.0 1.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]
               :mount/outline [[[-1 -1 1] [3 -1 1] [3 1 1] [-1 1 1]]]
               :mount/cut {:kind :pit :depth 0.25 :diameter 0.4 :mesh-key key}}]
    (try
      (pitting/save! deps source/id [mount] (:part/revision (part)))
      (is (= [(assoc mount :mount/accepts [:weapon])] (:part/mounts (part))))
      (is (pos? (:triangle-count (stl/parse-file! target))))
      (let [recess (assoc mount :mount/cut {:kind :recess :depth 0.25 :border 0.25 :mesh-key key})]
        (pitting/save! deps source/id [recess] (:part/revision (part)))
        (is (= :recess (get-in (part) [:part/mounts 0 :mount/cut :kind])))
        (let [before (Files/readAllBytes (.toPath target)) saved (part)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"border consumes"
                                (pitting/save! deps source/id [(assoc-in recess [:mount/cut :border] 10)] (:part/revision saved))))
          (is (= saved (part)))
          (is (Arrays/equals ^bytes before ^bytes (Files/readAllBytes (.toPath target))))))
      (is (Arrays/equals ^bytes original ^bytes (Files/readAllBytes (.toPath file))))
      (is (= 24 (count (geometry/mesh-triangles (stl/parse-file! file)))))
      (finally (fixture/stop! started)))))
