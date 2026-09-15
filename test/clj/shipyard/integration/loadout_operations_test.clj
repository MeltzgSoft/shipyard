(ns shipyard.integration.loadout-operations-test
  (:require [integrant.core :as ig]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout.operations :as operations]))

(defn- draft []
  {:revision 4 :hull (:hull fixture/ids)
   :assignments {[[:weapon 0]] (:weapon fixture/ids)
                 [[:weapon 0] [:turret 0]] (:turret fixture/ids)
                 [[:weapon 1]] (:weapon fixture/ids)}})

(deftest named-loadout-operations-revalidate-and-preserve-the-active-draft
  (let [{:keys [system temp] :as started} (fixture/start!)
        loadouts (ig/init-key :shipyard.loadout/store {:data-home temp})
        database (catalog/snapshot! (:shipyard.catalog/db system))
        available (set (vals fixture/ids))]
    (try
      (let [{saved :loadout} (operations/save! loadouts database (draft) available "  Dominator  ")]
        (is (= "Dominator" (:loadout/name saved)))
        (is (= [saved] (operations/list! loadouts)))
        (testing "loading returns the full nested, repeated assignment tree"
          (is (= (assoc (draft) :revision 5)
                 (:draft (operations/load! loadouts database {:revision 4 :hull nil :assignments {}}
                                           available (:loadout/id saved))))))
        (testing "duplicates receive an identity without changing their source"
          (let [{copy :loadout} (operations/duplicate! loadouts database available (:loadout/id saved) "Dominator II")]
            (is (not= (:loadout/id saved) (:loadout/id copy)))
            (is (= (:loadout/slots saved) (:loadout/slots copy)))))
        (is (= :missing-loadout (:error (operations/load! loadouts database (draft) available (random-uuid))))))
      (finally (fixture/stop! started)))))
