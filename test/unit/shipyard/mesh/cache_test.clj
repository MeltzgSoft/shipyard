(ns shipyard.mesh.cache-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shipyard.mesh.cache :as cache]))

(deftest eviction-plan-test
  (let [files [{:file :newest :size 40 :mtime 30}
               {:file :oldest :size 30 :mtime 10}
               {:file :middle :size 50 :mtime 20}]]
    (testing "oldest entries are selected until the remainder fits"
      (is (= {:files [:oldest :middle] :before 120 :after 40}
             (cache/eviction-plan files 60))))
    (testing "nothing is selected when already under the cap"
      (is (= {:files [] :before 120 :after 120}
             (cache/eviction-plan files 120))))))

(deftest eviction-ignores-in-progress-files-test
  (let [dir (fs/create-temp-dir)
        tier (fs/file dir (str (apply str (repeat 64 "a")) ".0.symesh"))
        temporary (fs/file dir "symesh-writing.tmp")]
    (try
      (spit tier "tier")
      (spit temporary "in progress")
      (cache/evict! {:dir dir :cap-bytes 0})
      (is (not (fs/exists? tier)))
      (is (fs/exists? temporary)
          "eviction must not observe or delete a preprocessor's temporary file")
      (finally
        (fs/delete-tree dir)))))
