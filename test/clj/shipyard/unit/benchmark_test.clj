(ns shipyard.unit.benchmark-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.benchmark :as benchmark]))

(deftest median-test
  (testing "an odd sample has one middle value"
    (is (= 2.0 (benchmark/median [3 1 2]))))
  (testing "an even sample averages its middle values"
    (is (= 2.5 (benchmark/median [4 1 3 2]))))
  (testing "an empty sample has no median"
    (is (nil? (benchmark/median [])))))

(deftest summarize-test
  (testing "the report preserves samples and exposes their useful bounds"
    (is (= {:runs 3
            :minimum 1.0
            :median 2.0
            :maximum 4.0
            :samples [4.0 1.0 2.0]}
           (benchmark/summarize [4 1 2]))))
  (testing "there is no summary without a measurement"
    (is (nil? (benchmark/summarize [])))))

(deftest parse-args-test
  (testing "defaults make the ordinary invocation complete"
    (is (= {:out "benchmark.edn"
            :runs benchmark/default-runs
            :viewport-seconds benchmark/default-viewport-seconds
            :viewport-mode :hardware}
           (benchmark/parse-args []))))
  (testing "value and flag options compose"
    (is (= {:out "/tmp/result.edn"
            :root "/models"
            :runs 5
            :viewport-seconds 20
            :viewport-mode :swiftshader
            :machine-label "workstation"
            :skip-canary? true
            :skip-viewport? true}
           (benchmark/parse-args ["--root" "/models"
                                  "--out" "/tmp/result.edn"
                                  "--runs" "5"
                                  "--viewport-seconds" "20"
                                  "--viewport-mode" "swiftshader"
                                  "--machine" "workstation"
                                  "--skip-canary"
                                  "--skip-viewport"]))))
  (testing "unknown options fail by name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--wat"
                          (benchmark/parse-args ["--wat"])))))

(deftest java->clj-test
  (testing "nested Java collections become keyword-keyed Clojure data"
    (let [inner (doto (java.util.ArrayList.) (.add "x"))
          outer (doto (java.util.HashMap.) (.put "items" inner))]
      (is (= {:items ["x"]} (benchmark/java->clj outer)))))
  (testing "scalar values pass through unchanged"
    (is (= 42 (benchmark/java->clj 42)))))
