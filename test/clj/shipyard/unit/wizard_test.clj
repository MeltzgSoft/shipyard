(ns shipyard.unit.wizard-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.wizard :as wizard]))

(def ^:private frame
  {:mount/pos [2.0 1.0 0.0]
   :mount/axis [0.0 0.0 1.0]
   :mount/roll [1.0 0.0 0.0]})

(def ^:private socket
  {:mount/id :port-1
   :mount/kind :socket
   :mount/accepts #{:weapon}
   :mount/pos [0.0 0.0 0.0]
   :mount/axis [0.0 0.0 1.0]
   :mount/roll [1.0 0.0 0.0]
   :mount/origin :picked})

(defn- params [overrides]
  (merge {"mount-id" "port-1"
          "kind" "socket"
          "accepts" "weapon"
          "part-role" "hull"
          "frame" (pr-str frame)
          "roll-deg" "0"
          "action" "create"}
         overrides))

(defn- close? [a b]
  (< (Math/abs (- (double a) (double b))) 1.0e-6))

(defn- vec-close? [a b]
  (every? (fn [[x y]] (close? x y)) (map vector a b)))

(deftest valid-frame?-test
  (testing "accepts finite orthonormal frames"
    (is (true? (wizard/valid-frame? frame))))
  (testing "rejects malformed or non-orthogonal frames"
    (is (false? (wizard/valid-frame? {:mount/pos [0 0] :mount/axis [0 0 1] :mount/roll [1 0 0]})))
    (is (false? (wizard/valid-frame? (assoc frame :mount/roll [0 0 1]))))))

(deftest rotate-roll-test
  (testing "rotates around the mount axis"
    (is (vec-close? [0.0 1.0 0.0]
                    (wizard/rotate-roll [0 0 1] [1 0 0] 90))))
  (testing "keeps the result normalized"
    (is (< (Math/abs (- 1.0 (Math/sqrt (reduce + (map #(* % %) (wizard/rotate-roll [0 0 1] [1 0 0] 33))))))
           1.0e-9))))

(deftest save-request-test
  (testing "creates a durable socket without facet indices"
    (let [{:keys [mount mounts part-role]} (wizard/save-request (params {}) [])]
      (is (= :hull part-role))
      (is (= [mount] mounts))
      (is (= #{:weapon} (:mount/accepts mount)))
      (is (nil? (:facet-indices mount)))))
  (testing "requires replace for duplicate ids"
    (is (:error (wizard/save-request (params {}) [socket])))
    (is (= 1 (count (:mounts (wizard/save-request (params {"action" "replace"
                                                           "part-role" "weapon"})
                                                  [socket]))))))
  (testing "allows only one plug at a time"
    (let [plug (assoc socket :mount/id :plug :mount/kind :plug)]
      (is (:error (wizard/save-request (params {"mount-id" "second" "kind" "plug"
                                                "part-role" "weapon"})
                                       [plug])))))
  (testing "rejects bad ids and malformed frames"
    (is (:error (wizard/save-request (params {"mount-id" "1 bad"}) [])))
    (is (:error (wizard/save-request (params {"frame" "{:not :a-frame}"}) [])))))

(deftest delete-request-test
  (testing "removes the named mount"
    (is (= [] (:mounts (wizard/delete-request {"mount-id" "port-1"} [socket])))))
  (testing "validates the id"
    (is (:error (wizard/delete-request {"mount-id" ""} [socket])))))
