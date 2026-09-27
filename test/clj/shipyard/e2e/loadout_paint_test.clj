(ns shipyard.e2e.loadout-paint-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.e2e.paint-material-test :as material-test]
            [shipyard.loadout.db :as loadouts]
            [shipyard.ship.db :as ships]
            [shipyard.scheme.db :as schemes]))

(deftest class-preview-is-independent-and-missing-scheme-keeps-custom-paint
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        class-id (random-uuid) scheme-id (random-uuid) ship-id (random-uuid)
        class {:loadout/id class-id :loadout/name "Cruiser" :loadout/hull (:hull fixture/ids) :loadout/slots {}}
        vessel {:ship/id ship-id :ship/name "Resolute" :ship/class class-id :ship/scheme scheme-id
                :ship/paint {:paint/instances {[] {:part-id (:hull fixture/ids) :material material-test/red}}}}]
    (try
      (loadouts/put! (:shipyard.loadout/db sys) class :create)
      (schemes/put! (:shipyard.scheme/db sys) {:scheme/id scheme-id :scheme/name "Blue" :scheme/roles {} :scheme/layers {"Primary" material-test/blue}} :create)
      (ships/put! (:shipyard.ship/db sys) vessel :create)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "ships")
      (s/open-class! driver "Cruiser")
      (is (s/wait-until #(= "9aa4af" (:color (material-test/slot driver [])))))
      (s/open-named-ship! driver ship-id)
      (is (s/wait-until #(= "ff0000" (:color (material-test/slot driver [])))))
      (schemes/delete! (:shipyard.scheme/db sys) scheme-id)
      (workspace/switch! driver "assembly")
      (named/tab! driver "Paint")
      (is (s/wait-until #(re-find #"Scheme unavailable" (s/text driver "#detail"))))
      (is (s/wait-until #(= "ff0000" (:color (material-test/slot driver [])))))
      (is (= vessel (get-in (ships/snapshot! (:shipyard.ship/db sys)) [:ships ship-id])))
      (named/tab! driver "Assembly")
      (is (s/wait-until #(= "9aa4af" (:color (material-test/slot driver [])))))
      (is (= class (get-in (loadouts/snapshot! (:shipyard.loadout/db sys)) [:loadouts class-id])))
      (finally (s/quit! driver) (fixture/stop! started)))))
