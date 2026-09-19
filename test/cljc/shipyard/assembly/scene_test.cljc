(ns shipyard.assembly.scene-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly.scene :as scene]))

(def set-a {:op :set :slot [[:weapon 0]] :part-id "weapon" :url "/mesh/a" :matrix [1]})
(def set-b (assoc set-a :slot [[:weapon 1]]))
(defn event [sequence commands] {:sequence sequence :revision 1 :commands commands})

(deftest leave-test
  (testing "mode changes invalidate pending work and clear identity"
    (let [state (scene/accept-event scene/empty-state (event 1 [{:op :reset} set-a]))
          token (get-in state [:slots (:slot set-a) :token])
          left (scene/leave state)]
      (is (= :browse (:mode left)))
      (is (empty? (:slots left)))
      (is (not (scene/current? left (:slot set-a) token))))))

(deftest accept-event-test
  (testing "duplicate part ids, nested slots, removal and identical resends"
    (let [nested (assoc set-a :slot [[:weapon 0] [:turret 0]] :part-id "turret")
          first (scene/accept-event scene/empty-state (event 1 [{:op :reset} set-a set-b nested]))
          second (scene/accept-event first (event 2 [set-a {:op :remove :slot (:slot set-b)}]))]
      (is (= 3 (count (:slots first))))
      (is (= 2 (count (:slots second))))
      (is (= (get-in first [:slots (:slot set-a) :token])
             (get-in second [:slots (:slot set-a) :token])))
      (is (contains? (:slots second) (:slot nested)))))
  (testing "old events cannot undo newer state and reset invalidates unchanged slot data"
    (let [state (scene/accept-event scene/empty-state (event 5 [{:op :reset} set-a]))
          reset (scene/accept-event state (event 6 [{:op :reset} set-a]))]
      (is (= state (scene/accept-event state (event 4 [{:op :reset}]))))
      (is (not= (get-in state [:slots (:slot set-a) :token])
                (get-in reset [:slots (:slot set-a) :token])))))
  (testing "late incremental responses cannot reopen assembly after browsing"
    (is (= scene/empty-state (scene/accept-event scene/empty-state (event 10 [set-a]))))))

(deftest current?-test
  (testing "out-of-order completion after replacement, removal or reset is stale"
    (let [state (scene/accept-event scene/empty-state (event 1 [{:op :reset} set-a]))
          token (get-in state [:slots (:slot set-a) :token])]
      (is (scene/current? state (:slot set-a) token))
      (doseq [command [(assoc set-a :part-id "replacement")
                       {:op :remove :slot (:slot set-a)} {:op :reset}]]
        (is (not (scene/current? (scene/accept-event state (event 2 [command])) (:slot set-a) token))))
      (is (not (scene/current? state (:slot set-a) nil))))))

(deftest material-updates-retain-pending-geometry
  (let [state (scene/accept-event scene/empty-state (event 1 [{:op :reset} set-a]))
        token (get-in state [:slots (:slot set-a) :token])
        material {:base [1 0 0] :metalness 0.5 :roughness 0.2}
        updated (scene/accept-event state (event 2 [(assoc set-a :material material)]))]
    (is (scene/current? updated (:slot set-a) token))
    (is (= material (get-in updated [:slots (:slot set-a) :payload :material])))
    (is (= updated (scene/accept-event updated (event 1 [set-a]))))))

(deftest details-update-the-latest-payload-without-invalidating-mesh-tokens
  (let [state (scene/accept-event scene/empty-state (event 1 [{:op :reset} set-a]))
        token (get-in state [:slots (:slot set-a) :token])
        details {:part-id "example" :mesh-key "hash" :faces {"face" [1 0 0]}}
        updated (scene/accept-event state (event 2 [(assoc set-a :details details)]))]
    (is (scene/current? updated (:slot set-a) token))
    (is (= details (get-in updated [:slots (:slot set-a) :payload :details])))
    (is (not (scene/current? (scene/leave updated) (:slot set-a) token)))))
