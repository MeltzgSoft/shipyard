(ns shipyard.http.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.http.jobs :as jobs]))

(deftest claim-job-test
  (testing "an unclaimed part receives the candidate"
    (is (= {"hull" {:state :running}}
           (jobs/claim-job {} "hull" {:state :running}))))
  (testing "an existing result wins"
    (is (= {"hull" {:state :ready :mesh-key "abc"}}
           (jobs/claim-job {"hull" {:state :ready :mesh-key "abc"}}
                           "hull"
                           {:state :running}))))
  (testing "a nil placeholder can still be claimed"
    (is (= {"hull" {:state :running}}
           (jobs/claim-job {"hull" nil} "hull" {:state :running})))))
