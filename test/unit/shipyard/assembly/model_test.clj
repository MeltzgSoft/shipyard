(ns shipyard.assembly.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.store.catalog-fixture :as d]
            [shipyard.assembly.model :as model]))

(def frame {:mount/pos [0 0 0] :mount/axis [0 0 1] :mount/roll [1 0 0]})
(def socket (merge frame {:mount/id :weapon :mount/kind :socket
                          :mount/accepts [:weapon] :mount/capacity 2
                          :mount/split {:direction :vertical :bounds [[-4 -2] [4 2]]}}))
(def plug (assoc frame :mount/id :plug :mount/kind :plug))
(def hull {:part/id "hull" :part/bundle "navy" :part/class :cruiser
           :part/role-source :manual :part/role-hint :hull
           :part/renderable true :part/source :unsupported :part/mounts [socket]})
(def weapon {:part/id "weapon" :part/bundle "navy" :part/class :cruiser
             :part/role-source :manual :part/role-hint :weapon
             :part/renderable true :part/source :unsupported
             :part/mounts [plug (merge frame {:mount/id :turret :mount/kind :socket
                                              :mount/accepts [:turret]})]})
(def turret (assoc weapon :part/id "turret" :part/role-hint :turret :part/mounts [plug]))
(def database (d/db-with (d/empty-db) [hull weapon turret]))

(deftest root-error-test
  (testing "root must be a renderable hull"
    (is (nil? (model/root-error hull)))
    (is (= :missing-part (model/root-error nil)))
    (is (= :unavailable-mesh (model/root-error (dissoc hull :part/source)))))
  (testing "scanned hull roles are usable without a redundant manual save"
    (doseq [source [:class :inferred]]
      (is (nil? (model/root-error (assoc hull :part/role-source source))))))
  (testing "a renderable non-hull cannot establish a root"
    (is (= :unauthored-hull (model/root-error (assoc hull :part/role-hint :weapon))))))

(deftest candidate-error-test
  (testing "an accepted role and exactly one valid plug authorize placement"
    (is (nil? (model/candidate-error hull :hull socket ["hull"] weapon)))
    (doseq [[candidate code]
            [[nil :missing-part]
             [(assoc weapon :part/renderable false) :unavailable-mesh]
             [(assoc weapon :part/bundle "other") :different-bundle]
             [(assoc weapon :part/class :escort) :different-class]
             [(assoc weapon :part/role-hint :unknown) :incompatible-role]
             [(assoc weapon :part/mounts []) :plug-count]
             [(assoc weapon :part/mounts [plug (assoc plug :mount/id :other)]) :plug-count]
             [(assoc weapon :part/mounts [(assoc plug :mount/axis [0 0 0])]) :invalid-plug]]]
      (is (= code (model/candidate-error hull :hull socket ["hull"] candidate)))))
  (testing "only ancestors prohibit reuse, not other slots"
    (is (= :cycle (model/candidate-error hull :hull socket ["hull" "weapon"] weapon))))
  (testing "single ship bundles are self-contained"
    (is (nil? (model/candidate-error (dissoc hull :part/class) :hull socket []
                                     (dissoc weapon :part/class))))
    (is (= :different-bundle
           (model/candidate-error (dissoc hull :part/class) :hull socket []
                                  (-> weapon (dissoc :part/class) (assoc :part/bundle "other")))))))

(deftest plug-parent-candidate-test
  (let [prow-plug (assoc plug :mount/id :prow)
        prow-socket (assoc socket :mount/id :hull :mount/accepts [:hull])
        plug-hull (assoc hull :part/mounts [prow-plug])
        prow (assoc weapon :part/id "prow" :part/role-hint :prow :part/mounts [prow-socket])
        reverse-database (d/db-with (d/empty-db) [plug-hull prow])]
    (testing "a hull plug offers its own selector to a prow socket that accepts hull"
      (is (nil? (model/candidate-error plug-hull :hull prow-plug ["hull"] prow)))
      (is (= ["prow"]
             (mapv :part/id (model/candidates reverse-database plug-hull :hull prow-plug ["hull"])))))
    (testing "a socket that does not accept the parent role cannot mate with the plug"
      (is (= :socket-count
             (model/candidate-error plug-hull :hull prow-plug ["hull"]
                                    (assoc prow :part/mounts [(assoc prow-socket :mount/accepts [:weapon])])))))
    (testing "plug slots are discovered alongside ordinary sockets"
      (let [result (model/slots reverse-database "hull" {[[:prow 0]] "prow"})]
        (is (empty? (:errors result)))
        (is (= [[[:prow 0]]] (mapv :id (:slots result))))
        (is (= :plug (get-in result [:slots 0 :mount :mount/kind])))))))

(deftest candidates-test
  (testing "Datascript join includes inferred roles but excludes unavailable parts and wrong class"
    (let [bad [(assoc weapon :part/id "hint" :part/role-source :inferred)
               (assoc weapon :part/id "supported" :part/renderable false)
               (assoc weapon :part/id "escort" :part/class :escort)]
          database (d/db-with database bad)]
      (is (= ["hint" "weapon"]
             (mapv :part/id (model/candidates database hull :hull socket ["hull"]))))))
  (testing "all accepted roles and an empty set"
    (doseq [role [:prow :bridge :antenna :weapon :turret]]
      (let [part (assoc weapon :part/id (name role) :part/role-hint role)
            database (d/db-with (d/empty-db) [part])]
        (is (= [(name role)] (mapv :part/id (model/candidates database hull :hull
                                                              (assoc socket :mount/accepts [role]) []))))))
    (is (empty? (model/candidates database hull :hull (assoc socket :mount/accepts []) [])))))

(deftest descendant?-test
  (testing "strict path prefix, not textual mount-name matching"
    (is (model/descendant? [[:weapon 0]] [[:weapon 0] [:turret 0]]))
    (is (not (model/descendant? [[:weapon 0]] [[:weapon 0]])))
    (is (not (model/descendant? [[:weapon 0]] [[:weapon 1] [:turret 0]])))))

(deftest prune-test
  (testing "replacement clears only the selected subtree"
    (let [assignments {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"
                       [[:weapon 0] [:turret 0]] "turret"}]
      (is (= {[[:weapon 1]] "weapon"} (model/prune assignments [[:weapon 0]])))
      (is (= {} (model/prune assignments [])))
      (is (= {} (model/prune {} [[:weapon 0]]))))))

(deftest slots-test
  (testing "capacity, duplicate printable parts and nested turrets preserve identity"
    (let [result (model/slots database "hull" {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"
                                               [[:weapon 0] [:turret 0]] "turret"})]
      (is (empty? (:errors result)))
      (is (= [[[:weapon 0]] [[:weapon 1]] [[:weapon 0] [:turret 0]] [[:weapon 1] [:turret 0]]]
             (mapv :id (:slots result))))
      (is (= [[-2.0 0.0 0.0] [2.0 0.0 0.0]]
             (mapv #(get-in % [:mount :mount/pos]) (take 2 (:slots result)))))))
  (testing "incomplete authoring and stale references are diagnostics"
    (doseq [[database hull-id assignments code]
            [[database "missing" {} :missing-part]
             [database "hull" {[[:gone 0]] "weapon"} :stale-slot]
             [database "hull" {[[:weapon 0]] "missing"} :missing-part]
             [(d/db-with (d/empty-db)
                         [(assoc hull :part/mounts [(dissoc socket :mount/split)])])
              "hull" {} :incomplete-split]]]
      (is (some #(= code (:code %)) (:errors (model/slots database hull-id assignments))))))
  (testing "cyclic assignments stop traversal"
    (let [cyclic (assoc weapon :part/mounts [plug (assoc socket :mount/capacity 1)])
          database (d/db-with (d/empty-db) [hull cyclic])
          result (model/slots database "hull" {[[:weapon 0]] "weapon"
                                               [[:weapon 0] [:weapon 0]] "weapon"})]
      (is (= [:cycle] (mapv :code (:errors result)))))))
