(ns shipyard.integration.loadout-paint-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]
            [shipyard.loadout.operations :as operations]
            [shipyard.scheme.db :as schemes]))

(deftest choosing-scheme-is-a-draft-change-until-save
  (let [started (fixture/start!) sys (:system started) deps (lf/deps started)
        handler (:handler started) state (:state (:assembly deps))
        id (random-uuid)]
    (try
      (schemes/put! (:shipyard.scheme/db sys) {:scheme/id id :scheme/name "Scheme" :scheme/roles {}} :create)
      (swap! state assoc :draft lf/draft :root (str (:root started)))
      (let [record (:loadout (operations/save! deps 1 "Original")) revision (get-in @state [:draft :revision])
            before (loadouts/snapshot! (:loadouts deps))
            post (fn [revision id] (handler (mock/request :post "/assembly/scheme" {:revision (str revision) :id id})))]
        (is (= 400 (:status (post revision "invalid"))))
        (is (= 422 (:status (post revision (str (random-uuid))))))
        (is (= 200 (:status (post revision (str id)))))
        (is (= id (get-in @state [:draft :scheme])))
        (is (operations/unsaved? deps))
        (is (= before (loadouts/snapshot! (:loadouts deps))))
        (is (= 422 (:status (post revision ""))))
        (operations/save! deps (get-in @state [:draft :revision]) "Original")
        (is (= id (get-in (persisted/records! (:loadouts deps) :loadouts)
                          [:loadouts (:loadout/id record) :loadout/scheme]))))
      (finally (fixture/stop! started)))))
