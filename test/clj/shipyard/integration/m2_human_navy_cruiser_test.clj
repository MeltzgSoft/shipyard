(ns shipyard.integration.m2-human-navy-cruiser-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.proof.m2-human-navy-cruiser :as proof]))

(defn- temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "shipyard-m2-human-navy-" (random-uuid)))
    (.mkdirs)))

(defn- write-part! [root part-id]
  (let [file (io/file root part-id "unsupported.stl")]
    (io/make-parents file)
    (with-open [out (io/output-stream file)]
      (.write out ^bytes (f/->binary-stl (f/cube 2.0))))
    file))

(defn- proof-library []
  (let [root (temp-dir)]
    (doseq [part-id (vals proof/parts)]
      (write-part! root part-id))
    root))

(deftest proof-writes-and-reloads-cruiser-authoring
  (let [root (proof-library)
        report (proof/run-proof! {:root root :cache-home (temp-dir)})]
    (testing "the proof covers every M2 mount kind and origin used by the Cruiser"
      (is (= {:parts 8
              :mounts 15
              :plugs 7
              :sockets 8
              :socket-capacity 10
              :mirrored-sockets 1
              :turret-sockets 3}
             (:totals report)))
      (is (empty? (:ambiguous-roll-cases report))))
    (testing "all authored metadata is durable and reloadable"
      (doseq [[_ {:keys [mount-count reloaded-mount-count reloaded-role part-role persisted?]}]
              (:authored report)]
        (is (= mount-count reloaded-mount-count))
        (is (= part-role reloaded-role))
        (is persisted?)))
    (testing "Cruiser weapon faces record two-module capacity"
      (let [hull (get-in report [:authored (:hull proof/parts)])
            by-id (into {} (map (juxt :mount/id identity)) (:mounts hull))]
        (is (= 2 (get-in by-id [:port-1 :mount/capacity])))
        (is (= 2 (get-in by-id [:starboard-1 :mount/capacity])))))
    (testing "facet-size evidence is recorded with each mount"
      (is (every? (fn [{:keys [bbox-face-span-mm]}]
                    (= 2 (count bbox-face-span-mm)))
                  (mapcat :mounts (vals (:authored report))))))
    (testing "the M3 audit makes incomplete capacity authoring actionable"
      (is (= :blocked (get-in report [:m3-assembly :status])))
      (is (some #(= :incomplete-split (:code %))
                (get-in report [:m3-assembly :diagnostics]))))
    (testing "every authored child sharing a socket role receives an attachment check"
      (let [checks (get-in report [:m3-assembly :attachment-checks])]
        (is (= 11 (count checks)))
        (is (= #{(:weapon proof/parts) (:lance proof/parts)}
               (set (map :child-part-id
                         (filter #(= :port-1 (:mount-id %)) checks)))))))))

(deftest proof-reports-missing-real-targets
  (let [root (temp-dir)]
    (write-part! root (:hull proof/parts))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required Human Navy Cruiser parts"
                          (proof/run-proof! {:root root :cache-home (temp-dir)})))))
