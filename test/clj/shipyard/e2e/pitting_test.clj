(ns shipyard.e2e.pitting-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.fixtures :as f]
            [shipyard.mesh.stl :as stl]
            [shipyard.pitting.geometry :as geometry]
            [shipyard.catalog.db :as catalog]
            [shipyard.pitting-internal-fixture :as internal])
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

(deftest save-pit-and-recess-on-a-hull-with-internal-face-pairs
  (s/assert-bundle!)
  (let [started (fixture/start! true internal/build! (fn [_])) sys (:system started)
        cat (:shipyard.catalog/db sys) driver (s/make-driver)
        file (io/file (str (:root started)) internal/id "unsupported.stl")
        target (io/file (.getParentFile file) "unsupported-pitted.stl")
        original (Files/readAllBytes (.toPath file))
        mounts #(get-in (catalog/part-context! cat internal/id) [:part :part/mounts])]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-prepared-part! driver sys "Joined hull" internal/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (let [{:keys [x y width height]} (s/bounds driver "#viewport")]
        (s/click-point! driver (+ x (/ width 2)) (+ y (/ height 2))))
      (s/wait-visible! driver ".mount-wizard__form")
      (s/select-option! driver "select[name=kind]" "socket")
      (s/click! driver "[name=create-pitted]")
      (s/fill-and-blur! driver "[name=cut-depth]" "0.25")
      (s/fill-and-blur! driver "[name=cut-diameter]" "0.4")
      (is (empty? (mounts)))
      (is (not (.exists target)))
      (s/click! driver "button[value=create]")
      (is (s/wait-until #(= 1 (count (mounts)))))
      (is (= :pit (get-in (first (mounts)) [:mount/cut :kind])))
      (is (.exists target))
      (is (pos? (:triangle-count (stl/parse-file! target))))
      (is (= 24 (:triangles (s/stats driver))) "the viewport retains all original source triangles")
      (s/click! driver "#detail button:text-is('Edit')")
      (s/wait-visible! driver ".mount-wizard__form")
      (s/select-option! driver "select[name=cut-kind]" "Recess")
      (s/fill-and-blur! driver "[name=cut-border]" "0.25")
      (s/click! driver "button[value=update]")
      (is (s/wait-until #(= :recess (get-in (first (mounts)) [:mount/cut :kind]))))
      (is (pos? (:triangle-count (stl/parse-file! target))))
      (is (Arrays/equals ^bytes original ^bytes (Files/readAllBytes (.toPath file))))
      (s/go! driver (s/base-url sys))
      (s/open-prepared-part! driver sys "Joined hull" internal/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (s/click! driver "#detail button:text-is('Edit')")
      (s/wait-visible! driver ".mount-wizard__form")
      (is (= "recess" (s/js driver "() => document.querySelector('[name=cut-kind]').value")))
      (is (= "0.25" (s/js driver "() => document.querySelector('[name=cut-depth]').value")))
      (is (seq (get-in (first (mounts)) [:mount/facet :indices])))
      (finally (s/quit! driver) (fixture/stop! started)))))
