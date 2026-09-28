(ns shipyard.integration.loadout-paint-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]
            [shipyard.loadout.operations :as operations]
            [shipyard.scheme.db :as schemes]))

(deftest classes-reject-scheme-assignment
  (let [started (fixture/start!) sys (:system started) deps (lf/deps started)
        handler (:handler started) state (:state (:assembly deps))
        id (random-uuid)]
    (try
      (schemes/put! (:shipyard.scheme/db sys) {:scheme/id id :scheme/name "Scheme" :scheme/layers {}} :create)
      (swap! state assoc :draft lf/draft :root (str (:root started)))
      (let [record (:loadout (operations/save! deps 1 "Original")) revision (get-in @state [:draft :revision])
            before (loadouts/snapshot! (:loadouts deps))
            post (fn [revision id] (handler (mock/request :post "/assembly/scheme" {:revision (str revision) :id id})))]
        (is (= 404 (:status (post revision (str id)))))
        (is (= :invalid-loadout (:error (loadouts/put! (:loadouts deps) (assoc record :loadout/scheme id) :update))))
        (is (nil? (get-in @state [:draft :scheme])))
        (is (not (operations/unsaved? deps)))
        (is (= before (loadouts/snapshot! (:loadouts deps))))
        (is (= record (get-in (persisted/records! (:loadouts deps) :loadouts)
                              [:loadouts (:loadout/id record)]))))
      (finally (fixture/stop! started)))))
