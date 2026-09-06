(ns shipyard.mesh.cache-test
  (:require [clojure.test :refer [deftest is testing]]
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
