(ns shipyard.bulk-orientation.save-state-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.part.orientation :as orientation]))

(def q45 (orientation/from-euler-degrees 45 0 0))
(def q90 (orientation/from-euler-degrees 90 0 0))
(def initial {"a" {:orientation q45 :saved orientation/identity-quaternion :dirty true}
              "b" {:orientation q45 :saved orientation/identity-quaternion :dirty true}})

(deftest submission-test
  (is (= {:request 1 :activation 10 :poses {"a" q45 "b" q45}}
         (saves/submission 1 10 initial)))
  (is (empty? (:poses (saves/submission 1 10 {"a" {:dirty false}})))))

(deftest same-pose-test
  (is (saves/same-pose? q45 (mapv - q45)))
  (is (not (saves/same-pose? q45 q90)))
  (is (not (saves/same-pose? nil q45))))

(deftest acknowledge-test
  (let [submitted (saves/submission 1 10 initial)
        response {:request 1 :activation 10 :saved ["a"]}
        newer (assoc-in initial ["a" :orientation] q90)
        after (saves/acknowledge newer submitted response 10)]
    (testing "partial acknowledgement preserves later edits and unsuccessful parts"
      (is (= q45 (get-in after ["a" :saved])))
      (is (= q90 (get-in after ["a" :orientation])))
      (is (true? (get-in after ["a" :dirty])))
      (is (= (get initial "b") (get after "b"))))
    (testing "no newer edit clears dirty; repeated acknowledgements are inert"
      (is (false? (get-in (saves/acknowledge initial submitted response 10) ["a" :dirty])))
      (is (= after (saves/acknowledge after submitted response 10))))
    (testing "stale activation, request, unsolicited id and older acknowledgement are inert"
      (is (= newer (saves/acknowledge newer submitted response 11)))
      (is (= newer (saves/acknowledge newer submitted (assoc response :request 2) 10)))
      (is (= newer (saves/acknowledge newer nil response 10)))
      (is (= newer (saves/acknowledge newer submitted (assoc response :saved ["missing"]) 10)))
      (let [later (saves/acknowledge newer (saves/submission 2 10 newer)
                                     (assoc response :request 2) 10)]
        (is (= q90 (get-in later ["a" :saved])))
        (is (= later (saves/acknowledge later submitted response 10)))))))

(deftest newer-request-test
  (is (saves/newer-request? nil [10 1]))
  (is (saves/newer-request? [10 2] [11 1]))
  (is (saves/newer-request? [10 2] [10 3]))
  (is (not (saves/newer-request? [10 2] [10 2])))
  (is (not (saves/newer-request? [10 2] [10 1]))))

(deftest restore-baseline-test
  (let [dirty {:orientation q90 :saved orientation/identity-quaternion :dirty true}
        restored (saves/restore-baseline dirty q45)]
    (is (= q45 (:saved restored)))
    (is (= q90 (:orientation restored)))
    (is (:dirty restored))
    (is (= q45 (:orientation (saves/restore-baseline (assoc dirty :dirty false) q45))))))
