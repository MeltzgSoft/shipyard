(ns shipyard.integration.loadout-operations-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as store]
            [shipyard.loadout.operations :as ops]))

(deftest explicit-save-preview-edit-and-duplicate
  (let [started (fixture/start!) deps (lf/deps started)
        state (get-in deps [:assembly :state]) preview (get-in deps [:preview :state])
        scheme (random-uuid)]
    (try
      (swap! state assoc :draft (assoc lf/draft :scheme scheme))
      (let [original (:loadout (ops/save! deps 1 "Cruiser")) id (:loadout/id original)
            file (:file (:loadouts deps)) bytes (slurp (str file))]
        (is (uuid? id))
        (is (= 1 (count (ops/list! deps {}))))
        (is (= 1 (count (ops/list! deps {:bundle "Synthetic Navy" :class "Cruiser"}))))
        (is (empty? (ops/list! deps {:bundle "Other"})))
        (testing "preview cannot change Assemble; full path identities survive"
          (let [before @state]
            (is (nil? (:error (ops/transfer! deps id :preview))))
            (is (= before @state))
            (is (= lf/assignments (get-in @preview [:draft :assignments])))))
        (testing "Duplicate has no durable effect and retains configuration"
          (is (nil? (:error (ops/transfer! deps id :duplicate))))
          (is (= "Cruiser - Copy" (get-in @state [:draft :name])))
          (is (= scheme (get-in @state [:draft :scheme])))
          (is (nil? (get-in @state [:draft :loadout-id])))
          (is (= bytes (slurp (str file))))
          (is (= 1 (count (ops/list! deps {}))))
          (let [copy (:loadout (ops/save! deps (get-in @state [:draft :revision]) "Cruiser - Copy"))
                records (:loadouts (store/snapshot! (store/open! file)))]
            (is (not= id (:loadout/id copy)))
            (is (= original (get records id)))
            (is (= copy (get records (:loadout/id copy))))
            (is (= 2 (count records)))))
        (testing "Edit itself does not write; Save updates only the explicit id"
          (let [before (slurp (str file))]
            (ops/transfer! deps id :edit)
            (is (= before (slurp (str file))))
            (is (= id (get-in @state [:draft :loadout-id])))
            (is (= id (:loadout/id (:loadout (ops/save! deps (get-in @state [:draft :revision]) "Renamed")))))
            (is (= 2 (count (ops/list! deps {})))))))
      (finally (fixture/stop! started)))))

(deftest failures-preserve-workspace-and-durable-state
  (let [started (fixture/start!) deps (lf/deps started) state (get-in deps [:assembly :state])]
    (try
      (swap! state assoc :draft lf/draft)
      (let [saved (:loadout (ops/save! deps 1 "A")) id (:loadout/id saved)
            before @state bytes (slurp (str (:file (:loadouts deps))))]
        (is (= :invalid-name (:error (ops/save! deps 2 " "))))
        (is (= :stale-revision (:error (ops/save! deps 0 "A"))))
        (is (= :missing-loadout (:error (ops/transfer! deps (random-uuid) :edit))))
        (is (= before @state))
        (catalog/save-part-role! (:catalog deps) (:prow fixture/ids) :weapon)
        (is (= :incompatible-role (:error (ops/transfer! deps id :duplicate))))
        (is (= before @state))
        (fs/delete (fs/path (:root started) (:hull fixture/ids) "unsupported.stl"))
        (is (= :unavailable-mesh (:error (ops/save! deps 2 "A"))))
        (is (= before @state))
        (is (= bytes (slurp (str (:file (:loadouts deps)))))))
      (finally (fixture/stop! started)))))

(deftest save-through-real-ring-boundary
  (let [started (fixture/start!) deps (lf/deps started) handler (:handler started)]
    (try
      (is (= 400 (:status (handler (mock/request :post "/assembly/save" {:revision "bad" :name "Ship"})))))
      (is (= 422 (:status (handler (mock/request :post "/assembly/save" {:revision "0" :name "Ship"})))))
      (swap! (get-in deps [:assembly :state]) assoc :draft lf/draft)
      (is (= 200 (:status (handler (mock/request :post "/assembly/save" {:revision "1" :name "Ship"})))))
      (is (= "Ship" (get-in (first (ops/list! deps {})) [:loadout :loadout/name])))
      (finally (fixture/stop! started)))))

(deftest scanned-roles-save-and-transfer-without-reauthoring
  (let [started (fixture/start! false lf/scanned-library!) deps (lf/deps started)
        state (get-in deps [:assembly :state])
        draft (assoc lf/draft :assignments lf/scanned-assignments)]
    (try
      (let [database (catalog/snapshot! (:catalog deps))]
        (is (= :inferred (:part/role-source (catalog/part database (:hull lf/scanned-ids)))))
        (is (= :class (:part/role-source (catalog/part database (:weapon lf/scanned-ids)))))
        (is (= :class (:part/role-source (catalog/part database (:turret lf/scanned-ids))))))
      (swap! state assoc :draft draft)
      (let [saved (:loadout (ops/save! deps 1 "Scanned Cruiser")) id (:loadout/id saved)
            file (:file (:loadouts deps))]
        (is (uuid? id))
        (is (= lf/scanned-assignments (:loadout/slots saved)))
        (is (= saved (get-in (store/snapshot! (store/open! file)) [:loadouts id])))
        (doseq [mode [:preview :edit :duplicate]]
          (let [result (ops/transfer! deps id mode)]
            (is (nil? (:error result)))
            (is (= lf/scanned-assignments (get-in result [:draft :assignments])))
            (is (= 13 (count (:scene result))))))
        (let [before @state bytes (slurp (str file))]
          (catalog/save-part-role! (:catalog deps) (:weapon lf/scanned-ids) :bridge)
          (is (= :incompatible-role
                 (:error (ops/save! deps (get-in @state [:draft :revision]) "Invalid"))))
          (is (= :incompatible-role (:error (ops/transfer! deps id :preview))))
          (is (= before @state))
          (is (= bytes (slurp (str file))))))
      (finally (fixture/stop! started)))))

(deftest incomplete-loadouts-round-trip
  (let [started (fixture/start!) deps (lf/deps started) state (get-in deps [:assembly :state])]
    (try
      (doseq [assignments [{} {[[:weapon 0]] (:weapon fixture/ids)}
                           {[[:weapon 0]] (:weapon fixture/ids)
                            [[:weapon 0] [:turret 0]] (:turret fixture/ids)}]]
        (swap! state assoc :draft (assoc lf/draft :assignments assignments :scheme (random-uuid)))
        (let [saved (:loadout (ops/save! deps 1 "Work in progress")) id (:loadout/id saved)
              file (:file (:loadouts deps)) bytes (slurp (str file))
              reloaded (assoc deps :loadouts (store/open! file))]
          (is (uuid? id))
          (is (= assignments (:loadout/slots saved)))
          (doseq [mode [:preview :edit :duplicate]]
            (let [result (ops/transfer! reloaded id mode)]
              (is (nil? (:error result)))
              (is (= assignments (get-in result [:draft :assignments])))
              (is (= (:loadout/scheme saved) (get-in result [:draft :scheme])))
              (is (= (inc (count assignments)) (count (:scene result))))))
          (is (= bytes (slurp (str file))))
          (let [copy (:loadout (ops/save! deps (get-in @state [:draft :revision]) "Partial copy"))
                records (:loadouts (store/snapshot! (store/open! file)))]
            (is (not= id (:loadout/id copy)))
            (is (= assignments (:loadout/slots copy)))
            (is (= saved (get records id))))))
      (finally (fixture/stop! started)))))

(deftest deletion-preserves-work-until-commit
  (let [started (fixture/start!) deps (lf/deps started)
        state (get-in deps [:assembly :state]) preview (get-in deps [:preview :state])]
    (try
      (swap! state assoc :draft (assoc lf/draft :scheme (random-uuid)))
      (let [record (:loadout (ops/save! deps 1 "Cruiser")) id (:loadout/id record)
            file (:file (:loadouts deps)) blocked (fs/path (:temp started) "blocked")]
        (ops/transfer! deps id :preview)
        (let [draft (:draft @state) before @state preview-before @preview
              bytes (slurp (str file))]
          (spit (fs/file blocked) "not a directory")
          (is (= :store-write-failed
                 (:error (ops/delete! (assoc-in deps [:loadouts :file] (fs/path blocked "file")) id))))
          (is (= before @state))
          (is (= preview-before @preview))
          (is (= bytes (slurp (str file))))
          (is (= :missing-loadout (:error (ops/delete! deps (random-uuid)))))
          (is (= before @state))
          (is (= preview-before @preview))
          (is (= {:deleted id} (ops/delete! deps id)))
          (is (= (-> draft (dissoc :loadout-id) (update :revision inc)) (:draft @state)))
          (is (nil? (get-in @preview [:draft :hull])))
          (is (empty? (:loadouts (store/snapshot! (store/open! file)))))
          (let [copy (:loadout (ops/save! deps (get-in @state [:draft :revision]) "Recovered"))]
            (is (uuid? (:loadout/id copy)))
            (is (not= id (:loadout/id copy)))
            (is (= (:loadout/slots record) (:loadout/slots copy))))))
      (testing "missing parts do not block deleting a record"
        (let [record {:loadout/id (random-uuid) :loadout/name "Missing" :loadout/hull "gone" :loadout/slots {}}]
          (store/put! (:loadouts deps) record :create)
          (is (= {:deleted (:loadout/id record)} (ops/delete! deps (:loadout/id record))))))
      (finally (fixture/stop! started)))))
