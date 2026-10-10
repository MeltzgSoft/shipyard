(ns shipyard.integration.assembly-compatibility-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.compatibility-fixture :as compatibility]
            [shipyard.loadout.db :as loadouts]
            [shipyard.loadout.model :as model]
            [shipyard.loadout.operations :as operations]
            [shipyard.loadout-fixture :as lf]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.paint.db :as paint]
            [shipyard.vocabulary.db :as vocabulary]))

(deftest compatibility-http-save-reopen-and-duplicate
  (let [started (fixture/start! false compatibility/library! compatibility/author!)
        handler (:handler started) deps (lf/deps started) state (get-in deps [:assembly :state])
        post! #(handler (mock/request :post %1 %2))
        revision #(str (get-in @state [:draft :revision]))
        toggle! #(post! "/assembly/compatibility" (cond-> {:revision (revision)} % (assoc :allow-other-factions "true")))
        assign! #(post! "/assembly/assign" {:revision (revision) :slot %1 :part-id %2})]
    (try
      (is (= 200 (:status (post! "/assembly/hull" {:revision "0" :part-id (:hull fixture/ids)}))))
      (is (= 422 (:status (assign! "[[:weapon 0]]" compatibility/foreign))))
      (is (= 200 (:status (assign! "[[:weapon 1]]" (:weapon-alt fixture/ids)))))
      (is (= 422 (:status (assign! "[[:weapon 0]]" compatibility/escort))))
      (is (= 400 (:status (post! "/assembly/compatibility" {:revision (revision) :allow-other-factions "false"}))))
      (is (= 409 (:status (post! "/assembly/compatibility" {:revision "0" :allow-other-factions "true"}))))
      (is (= 200 (:status (toggle! true))))
      (is (= 200 (:status (assign! "[[:weapon 0]]" compatibility/foreign-universal))))
      (is (= 200 (:status (assign! "[[:weapon 0] [:turret 0]]" (:turret fixture/ids)))))
      (let [before (:draft @state) scene (:scene @state) response (toggle! false)]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "Clear parts from other factions"))
        (is (= before (:draft @state)))
        ;; Source preparation may finish while a rejected command refreshes the
        ;; scene. Its derived source key and summary state are not placements.
        (is (= (update-vals scene #(dissoc % :mesh-key :emission))
               (update-vals (:scene @state) #(dissoc % :mesh-key :emission)))))
      (is (= 200 (:status (post! "/assembly/save" {:revision (revision) :name "Mixed Cruiser"}))))
      (let [id (get-in @state [:draft :loadout-id]) record (loadouts/record! (:loadouts deps) id)]
        (is (true? (:loadout/allow-other-factions? record)))
        (is (= record (get-in (persisted/records! (:loadouts deps) :loadouts) [:loadouts id])))
        (let [sys (:system started)
              paint-deps (assoc deps :paint (:shipyard.paint/db sys) :named-ships (:shipyard.ship/db sys)
                                :schemes (:shipyard.scheme/db sys))]
          (is (nil? (:error (paint/transfer! paint-deps (:assembly deps) nil))))
          (is (true? (get-in @(:state (:paint paint-deps)) [:draft :allow-other-factions?])))
          (paint/refresh! paint-deps)
          (is (true? (get-in @(:state (:paint paint-deps)) [:draft :allow-other-factions?]))))
        (is (number? (model/empty-mount-count (catalog/assembly-snapshot! (:catalog deps)) record)))
        (doseq [mode [:preview :edit :duplicate]]
          (let [result (operations/transfer! deps id mode)]
            (is (nil? (:error result)))
            (is (true? (get-in result [:draft :allow-other-factions?])))
            (is (= 4 (count (:scene result))))))
        (is (false? (operations/unsaved? (assoc deps :assembly (:preview deps)))))
        (is (= 200 (:status (post! "/assembly/clear" {:revision (revision) :slot "[[:weapon 0]]"}))))
        (is (= 200 (:status (toggle! false))))
        (is (= 200 (:status (post! "/assembly/save" {:revision (revision) :name "Local Cruiser"}))))
        (let [local (loadouts/record! (:loadouts deps) (get-in @state [:draft :loadout-id]))]
          (is (not (:loadout/allow-other-factions? local)))
          (is (not (:allow-other-factions? (:draft (operations/transfer! deps (:loadout/id local) :edit)))))))
      (finally (fixture/stop! started)))))

(deftest universal-is-shared-and-protected-in-settings
  (let [started (fixture/start!) sys (:system started) database (:shipyard.store/db sys)
        handler (:handler started)]
    (try
      (is (contains? (:class (vocabulary/choices! (:shipyard.catalog/db sys))) "Universal"))
      (is (true? (:builtin? (some #(when (= "Universal" (:value %)) %) (:class (vocabulary/entries! database))))))
      (handler (mock/request :get "/workspace/settings"))
      (doseq [action ["rename" "delete"]]
        (is (= 422 (:status (handler (mock/request :post (str "/settings/classifications/" action)
                                                   {:field "class" :value "Universal" :new-value "Anything"}))))))
      (is (contains? (:class (vocabulary/registered! database)) "Universal"))
      (finally (fixture/stop! started)))))
