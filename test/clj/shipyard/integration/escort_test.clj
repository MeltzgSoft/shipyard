(ns shipyard.integration.escort-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.fixtures :as f]
            [shipyard.library.escort :as escort]))

(defn- temp-dir ^java.io.File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- transform [tris [sx sy sz] [dx dy dz]]
  (mapv (fn [tri]
          (mapv (fn [[x y z]]
                  [(+ dx (* sx x)) (+ dy (* sy y)) (+ dz (* sz z))])
                tri))
        tris))

(defn- write-part! [root id scale]
  (let [file (io/file root id "unsupported.stl")]
    (io/make-parents file)
    (with-open [out (io/output-stream file)]
      (.write out ^bytes (f/->binary-stl (transform (f/cube 2.0) scale [0.0 0.0 0.0]))))
    file))

(defn- fixture-tree []
  (let [root (temp-dir "shipyard-escort")]
    (write-part! root "Human Navy Fleet Bundle/Escort/Cyanide Prow Python" [30.0 9.5 14.0])
    (write-part! root "Human Navy Fleet Bundle/Escort/Azure Prow Python" [36.0 9.55 14.1])
    (write-part! root "Pirate Elves Fleet Bundle/Escort/Raider Hull" [30.0 10.0 8.0])
    (write-part! root "Pirate Elves Fleet Bundle/Escort/Raider Prow" [10.0 4.0 3.0])
    (write-part! root "Hazard Stripe Fleet Bundle/Escort/IW Python" [32.0 8.0 10.0])
    (write-part! root "Hazard Stripe Fleet Bundle/Escort/IW Lance Python" [38.0 8.1 10.05])
    (write-part! root "Hazard Stripe Fleet Bundle/Escort/IW Barge Hull" [30.0 10.0 8.0])
    (write-part! root "Hazard Stripe Fleet Bundle/Escort/IW Barge Prow" [10.0 4.0 3.0])
    (write-part! root "Mystery Fleet Bundle/Escort/Lone Prow" [10.0 4.0 3.0])
    root))

(defn- by-id [report]
  (into {} (map (juxt :part/id identity)) report))

(deftest analyze-library-classifies-escort-families
  (let [root (fixture-tree)
        cache-home (temp-dir "shipyard-escort-cache")
        library (ig/init-key :shipyard.library/index {:root (str root)
                                                      :cache-home (str cache-home)})
        first-report (escort/analyze-library! library)
        second-report (escort/analyze-library! library)
        report (by-id first-report)]
    (testing "known whole-ship Human Navy-style families become geometry-sourced ships"
      (let [row (report "Human Navy Fleet Bundle/Escort/Cyanide Prow Python")]
        (is (= :whole-ship (get-in row [:classification :escort/classification])))
        (is (= :ship (get-in row [:part :part/role-hint])))
        (is (= :geometry (get-in row [:part :part/role-source])))))
    (testing "known Pirate Elf-style kitbash components need a larger sibling anchor"
      (is (= :kitbash-component
             (get-in report ["Pirate Elves Fleet Bundle/Escort/Raider Prow"
                             :classification :escort/classification]))))
    (testing "mixed escort folders can contain both whole ships and components"
      (is (= :whole-ship
             (get-in report ["Hazard Stripe Fleet Bundle/Escort/IW Python"
                             :classification :escort/classification])))
      (is (= :kitbash-component
             (get-in report ["Hazard Stripe Fleet Bundle/Escort/IW Barge Prow"
                             :classification :escort/classification]))))
    (testing "unresolved synthetic cases remain visible"
      (is (= :unresolved
             (get-in report ["Mystery Fleet Bundle/Escort/Lone Prow"
                             :classification :escort/classification]))))
    (testing "analysis is cached in the scan index"
      (is (= (mapv :measurement first-report)
             (mapv :measurement second-report)))
      (is (fs/regular-file? (first (fs/glob cache-home "shipyard/index-*.edn")))))))
