(ns shipyard.library.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.library.index :as index]))

(deftest fresh-test
  (testing "mtime and size must both match"
    (is (index/fresh? {:mtime 10 :size 20 :mesh-key "abc"}
                      {:mtime 10 :size 20}))
    (is (not (index/fresh? {:mtime 10 :size 20}
                           {:mtime 11 :size 20}))))
  (testing "an absent entry is never fresh"
    (is (not (index/fresh? nil {:mtime 10 :size 20})))))

(deftest refresh-test
  (let [parts [{:part/id "hull" :part/source :unsupported}
               {:part/id "prow" :part/source :unsupported}
               {:part/id "supported-only" :part/source nil}]
        stored {"hull" {:mtime 10 :size 20 :mesh-key "keep" :tris 12
                        :escort-analysis {:volume 42.0}}
                "prow" {:mtime 10 :size 20 :mesh-key "drop" :tris 6}
                "supported-only" {:mesh-key "drop"}}
        stats {"hull" {:mtime 10 :size 20}
               "prow" {:mtime 11 :size 20}}
        result (index/refresh parts stored stats)]
    (testing "unchanged sources retain derived metadata"
      (is (= {:mtime 10 :size 20 :mesh-key "keep" :tris 12
              :escort-analysis {:volume 42.0}}
             (get result "hull"))))
    (testing "changed and unrenderable sources lose derived metadata"
      (is (= {:mtime 11 :size 20} (get result "prow")))
      (is (= {} (get result "supported-only"))))))

(deftest with-escort-analysis-test
  (let [state {:entries {"hull" {:mtime 10 :size 20}}}
        analysis {:volume 42.0}]
    (testing "existing parts gain cached analysis"
      (is (= analysis
             (get-in (index/with-escort-analysis state "hull" analysis)
                     [:entries "hull" :escort-analysis]))))
    (testing "completed work cannot leak into a removed part"
      (is (= state (index/with-escort-analysis state "missing" analysis))))))
