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
          "capacity" "1"
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
    (let [{:keys [mount mounts]} (wizard/save-request (params {}) [])]
      (is (= [mount] mounts))
      (is (= #{:weapon} (:mount/accepts mount)))
      (is (= 1 (:mount/capacity mount)))
      (is (nil? (:facet-indices mount)))))
  (testing "records socket capacity when one face holds multiple parts"
    (let [{:keys [mount]} (wizard/save-request (params {"capacity" "2"}) [])]
      (is (= 2 (:mount/capacity mount)))))
  (testing "normalizes finite geometry frames before saving"
    (let [drifty-frame (assoc frame
                              :mount/axis [0.0 0.0 0.999998]
                              :mount/roll [1.0 0.0 1.0e-5])
          {:keys [mount]} (wizard/save-request (params {"frame" (pr-str drifty-frame)}) [])]
      (is (wizard/valid-frame? (select-keys mount [:mount/pos :mount/axis :mount/roll])))
      (is (vec-close? [0.0 0.0 1.0] (:mount/axis mount)))
      (is (vec-close? [1.0 0.0 0.0] (:mount/roll mount)))))
  (testing "rejects invalid socket capacities"
    (is (:error (wizard/save-request (params {"capacity" "0"}) [])))
    (is (:error (wizard/save-request (params {"capacity" "1.5"}) []))))
  (testing "ignores capacity on plugs"
    (let [{:keys [mount]} (wizard/save-request (params {"mount-id" "plug"
                                                        "kind" "plug"
                                                        "capacity" "2"})
                                               [])]
      (is (= :plug (:mount/kind mount)))
      (is (nil? (:mount/capacity mount)))))
  (testing "requires replace for duplicate ids"
    (is (:error (wizard/save-request (params {}) [socket])))
    (is (= 1 (count (:mounts (wizard/save-request (params {"action" "replace"})
                                                  [socket]))))))
  (testing "allows only one plug at a time"
    (let [plug (assoc socket :mount/id :plug :mount/kind :plug)]
      (is (:error (wizard/save-request (params {"mount-id" "second" "kind" "plug"})
                                       [plug])))))
  (testing "rejects bad ids and malformed frames"
    (is (:error (wizard/save-request (params {"mount-id" "1 bad"}) [])))
    (is (:error (wizard/save-request (params {"frame" "{:not :a-frame}"}) [])))))

(deftest error-preview-test
  (testing "preserves the selected face and valid form choices after a save error"
    (let [preview (wizard/error-preview {:part/id "part-1"}
                                        (params {"capacity" "2"
                                                 "roll-deg" "90"})
                                        "Already exists")]
      (is (= "Already exists" (:error preview)))
      (is (= "port-1" (get-in preview [:values :mount-id])))
      (is (= 2 (get-in preview [:values :capacity])))
      (is (vec-close? [0.0 1.0 0.0] (get-in preview [:frame :mount/roll])))))
  (testing "still returns the form values when the frame itself is invalid"
    (let [preview (wizard/error-preview {:part/id "part-1"}
                                        (params {"frame" "{:not :a-frame}"})
                                        "Pick it again")]
      (is (= "Pick it again" (:error preview)))
      (is (= "port-1" (get-in preview [:values :mount-id])))
      (is (nil? (:frame preview))))))

(deftest part-role-request-test
  (testing "parses a valid part role separately from mount authoring"
    (is (= {:part-role :hull} (wizard/part-role-request {"part-role" "hull"}))))
  (testing "rejects missing or unsupported part roles"
    (is (:error (wizard/part-role-request {})))
    (is (:error (wizard/part-role-request {"part-role" "spaceship"})))))

(deftest suggest-mirror-id-test
  (testing "uses deterministic port and starboard counterparts"
    (is (= :starboard-1 (wizard/suggest-mirror-id :port-1)))
    (is (= :port-2 (wizard/suggest-mirror-id :starboard-2))))
  (testing "falls back to a deterministic mirror suffix"
    (is (= :prow-mirror (wizard/suggest-mirror-id :prow)))))

(deftest suggest-repeat-id-test
  (testing "increments numeric suffixes without colliding"
    (is (= :port-3 (wizard/suggest-repeat-id [{:mount/id :port-2}] :port-1))))
  (testing "adds a numeric suffix when there is none"
    (is (= :socket-2 (wizard/suggest-repeat-id [] :socket)))))

(deftest mirror-frame-test
  (testing "mirrors all supported coordinate planes"
    (is (= {:mount/pos [-2.0 1.0 0.0]
            :mount/axis [-0.0 0.0 1.0]
            :mount/roll [-1.0 0.0 0.0]}
           (wizard/mirror-frame frame :x 0.0)))
    (is (= {:mount/pos [2.0 -1.0 0.0]
            :mount/axis [0.0 -0.0 1.0]
            :mount/roll [1.0 -0.0 0.0]}
           (wizard/mirror-frame frame :y 0.0)))
    (is (= {:mount/pos [2.0 1.0 4.0]
            :mount/axis [0.0 0.0 -1.0]
            :mount/roll [1.0 0.0 -0.0]}
           (wizard/mirror-frame frame :z 2.0))))
  (testing "off-origin planes and orientation validity"
    (let [mirrored (wizard/mirror-frame frame :x 1.0)]
      (is (= [0.0 1.0 0.0] (:mount/pos mirrored)))
      (is (true? (wizard/valid-frame? mirrored))))))

(deftest mirrored-save-request-test
  (testing "creates picked and mirrored sockets"
    (let [{:keys [mount mirrored-mount mounts repeat-values]}
          (wizard/save-request (params {"mount-id" "port-1"
                                        "mirror" "true"
                                        "mirror-plane" "x"
                                        "mirror-offset" "0"
                                        "mirror-id" "starboard-1"
                                        "repeat" "true"})
                               [])]
      (is (= :picked (:mount/origin mount)))
      (is (= :mirrored (:mount/origin mirrored-mount)))
      (is (= [:port-1 :starboard-1] (mapv :mount/id mounts)))
      (is (= [1 1] (mapv :mount/capacity mounts)))
      (is (= {:mount-id "port-2"
              :kind "socket"
              :accepts #{:weapon}
              :capacity 1}
             repeat-values))))
  (testing "mirrors and repeats capacity"
    (let [{:keys [mounts repeat-values]}
          (wizard/save-request (params {"capacity" "2"
                                        "mirror" "true"
                                        "mirror-plane" "x"
                                        "mirror-id" "starboard-1"
                                        "repeat" "true"})
                               [])]
      (is (= [2 2] (mapv :mount/capacity mounts)))
      (is (= 2 (:capacity repeat-values)))))
  (testing "rejects centerline mounts and id conflicts"
    (is (:error (wizard/save-request (params {"mount-id" "port-1"
                                              "mirror" "true"
                                              "mirror-plane" "x"
                                              "mirror-offset" "2"
                                              "mirror-id" "starboard-1"})
                                     [])))
    (is (:error (wizard/save-request (params {"mount-id" "port-1"
                                              "mirror" "true"
                                              "mirror-plane" "x"
                                              "mirror-id" "starboard-1"})
                                     [(assoc socket :mount/id :starboard-1)])))))

(deftest delete-request-test
  (testing "removes the named mount"
    (is (= [] (:mounts (wizard/delete-request {"mount-id" "port-1"} [socket])))))
  (testing "validates the id"
    (is (:error (wizard/delete-request {"mount-id" ""} [socket])))))
