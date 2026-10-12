(ns shipyard.integration.fleet-export-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.fleet.export :as export])
  (:import [java.util.zip ZipFile]))

(defn fixture! []
  (let [home (fs/create-temp-dir {:prefix "fleet-export-"})
        library (fs/create-dirs (fs/path home "library"))
        relative "Bundle/Part, one/unsupported.stl"
        source (fs/path library relative)]
    (fs/create-dirs (fs/parent source))
    (spit (str source) "original STL bytes")
    {:home home :library library :source source :destination (fs/path home "exports")
     :snapshot {:fleet {:fleet/library :library :fleet/entries {} :fleet/extras {"part" 7}
                        :fleet/variant-preference [:unsupported]}
                :classes {}
                :parts {"part" {:part/library :library :part/present? true
                                :part/sources {:unsupported {:source/key [:part :unsupported]
                                                             :source/variant :unsupported :source/present? true
                                                             :source/path relative :source/size (fs/size source)
                                                             :source/mtime (.toMillis ^java.nio.file.attribute.FileTime (fs/last-modified-time source))}}}}}}))

(deftest complete-and-unique-exports
  (let [{:keys [home snapshot library destination]} (fixture!)]
    (try
      (testing "archive preserves canonical source paths, bytes, quoted CSV and quantities"
        (let [first-export (export/export! snapshot library destination)
              second-export (export/export! snapshot library destination)]
          (is (not= (:path first-export) (:path second-export)))
          (with-open [zip (ZipFile. (:path first-export))]
            (is (= 2 (.size zip)))
            (with-open [input (.getInputStream zip (.getEntry zip "Bundle/Part, one/unsupported.stl"))]
              (is (= "original STL bytes" (slurp input))))
            (with-open [input (.getInputStream zip (.getEntry zip "index.csv"))]
              (is (= "path,variant,quantity\r\n\"Bundle/Part, one/unsupported.stl\",unsupported,7\r\n" (slurp input)))))))
      (finally (fs/delete-tree home)))))

(deftest invalid-source-and-destination-leave-no-bundle
  (let [{:keys [home snapshot library source destination]} (fixture!)]
    (try
      (testing "export destination inside library is rejected before creating it"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside" (export/export! snapshot library (fs/path library "export"))))
        (is (not (fs/exists? (fs/path library "export")))))
      (testing "missing and changed sources prevent publication"
        (spit (str source) "changed")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed" (export/export! snapshot library destination)))
        (fs/delete source)
        (is (thrown? clojure.lang.ExceptionInfo (export/export! snapshot library destination)))
        (is (empty? (fs/list-dir destination))))
      (finally (fs/delete-tree home)))))

(deftest failures-during-copy-clean-up-and-preserve-prior-bundles
  (let [{:keys [home snapshot library source destination]} (fixture!)]
    (try
      (let [prior (export/export! snapshot library destination)
            copy! io/copy]
        (testing "source mutation during copy is detected even with restored size and mtime"
          (with-redefs [io/copy (fn [input output]
                                  (copy! input output)
                                  (let [stamp (fs/last-modified-time source)]
                                    (spit (str source) "modified STL bytes")
                                    (fs/set-last-modified-time source stamp)))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed" (export/export! snapshot library destination)))))
        (is (= [(:path prior)] (mapv (comp str fs/real-path) (fs/list-dir destination)))))
      (finally (fs/delete-tree home)))))

(deftest reference-revalidation-failure-cleans-up
  (let [{:keys [home snapshot library destination]} (fixture!)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"references changed"
                            (export/export! snapshot library destination
                                            #(throw (ex-info "Fleet references changed" {:type :fleet-export})))))
      (is (empty? (fs/list-dir destination)))
      (finally (fs/delete-tree home)))))

(deftest source-change-during-reference-revalidation-prevents-publication
  (let [{:keys [home snapshot library source destination]} (fixture!)]
    (try
      (let [prior (export/export! snapshot library destination)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed before publication"
                              (export/export! snapshot library destination
                                              #(let [stamp (fs/last-modified-time source)]
                                                 (spit (str source) "modified STL bytes")
                                                 (fs/set-last-modified-time source stamp)))))
        (is (= [(:path prior)] (mapv (comp str fs/real-path) (fs/list-dir destination)))))
      (finally (fs/delete-tree home)))))

(deftest concurrent-exports-never-overwrite
  (let [{:keys [home snapshot library destination]} (fixture!)]
    (try
      (let [exports (mapv deref (vec (repeatedly 4 #(future (export/export! snapshot library destination)))))]
        (is (= 4 (count (set (map :path exports)))))
        (is (= 4 (count (fs/list-dir destination))))
        (doseq [{:keys [path]} exports]
          (with-open [zip (ZipFile. ^String path)]
            (is (= 2 (.size zip))))))
      (finally (fs/delete-tree home)))))

(deftest source-symlink-cannot-escape-library
  (let [{:keys [home snapshot library source destination]} (fixture!)]
    (try
      (let [outside (fs/path home "outside.stl")]
        (spit (str outside) "original STL bytes")
        (fs/delete source)
        (try
          (fs/create-sym-link source outside)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes" (export/export! snapshot library destination)))
          (is (empty? (fs/list-dir destination)))
          ;; Windows runners may not hold the privilege required to create symlinks.
          (catch java.nio.file.FileSystemException _
            (is (fs/windows?)))))
      (finally (fs/delete-tree home)))))
