(ns shipyard.integration.index-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.library.index :as index]))

(defn- temp-dir ^java.io.File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- source! [root part-id]
  (let [f (io/file root part-id "unsupported.stl")]
    (io/make-parents f)
    (spit f "mesh")
    f))

(deftest refresh-carries-fresh-escort-analysis
  (let [root (temp-dir "shipyard-index")
        part-id "Human Navy Fleet Bundle/Escort/Cyanide Prow Python"
        f (source! root part-id)
        parts [{:part/id part-id :part/source :unsupported}]
        entry (get (index/refresh parts root {}) part-id)
        stored {part-id (assoc entry
                               :escort-analysis {:volume 42.0})}]
    (testing "fresh source files keep cached escort geometry analysis"
      (is (= {:volume 42.0}
             (get-in (index/refresh parts root stored)
                     [part-id :escort-analysis]))))
    (testing "changed source files drop cached escort geometry analysis"
      (Thread/sleep 2)
      (spit f "changed")
      (is (nil? (get-in (index/refresh parts root stored)
                        [part-id :escort-analysis]))))
    (testing "the test did not leave the temp root missing before assertions"
      (is (fs/directory? root)))))
