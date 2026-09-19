(ns shipyard.e2e.loadout-paint-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.paint-material-test :as material-test]
            [shipyard.loadout.db :as loadouts]
            [shipyard.scheme.db :as schemes]))

(deftest override-save-copy-clear-and-dangling-reference
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        store (:shipyard.loadout/db sys) scheme-store (:shipyard.scheme/db sys)
        state (:state (:shipyard.assembly/db sys))
        red-id (random-uuid) blue-id (random-uuid) ship-id (random-uuid)
        source {:loadout/id ship-id :loadout/name "Original" :loadout/hull (:hull fixture/ids)
                :loadout/slots {} :loadout/scheme red-id}
        card (str ".ship-card[data-loadout-id='" ship-id "']")]
    (try
      (doseq [[id name material] [[red-id "Red" material-test/red] [blue-id "Blue" material-test/blue]]]
        (schemes/put! scheme-store {:scheme/id id :scheme/name name :scheme/roles {:hull material}} :create))
      (loadouts/put! store source :create)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "ships")
      (s/click! driver (str card " button:text-is('Edit')"))
      (s/wait-visible! driver ".assembly__scheme")
      (s/click! driver "[data-mount-colors-toggle]")
      (is (s/wait-until #(= "ff0000" (:color (material-test/slot driver [])))))
      (let [before (slurp (str (:file store)))]
        (s/select-option! driver ".assembly__scheme select" "Blue")
        (is (s/wait-until #(= blue-id (get-in @state [:draft :scheme]))))
        (is (s/wait-until #(= "0000ff" (:color (material-test/slot driver [])))))
        (is (= before (slurp (str (:file store)))))
        (s/click! driver ".assembly__save button")
        (is (s/wait-until #(= blue-id (get-in (loadouts/snapshot! store) [:loadouts ship-id :loadout/scheme])))))
      (workspace/switch! driver "ships")
      (s/click! driver (str card " .ship-card__load"))
      (is (s/wait-until #(= "0000ff" (:color (material-test/slot driver [])))))
      (is (= "rgb(0, 0, 255)" (s/js driver "() => getComputedStyle(document.querySelector('.ship-tree__color')).backgroundColor")))
      (let [before (slurp (str (:file store)))]
        (s/click! driver (str card " button:text-is('Duplicate')"))
        (s/wait-visible! driver ".assembly__save")
        (is (= blue-id (get-in @state [:draft :scheme])))
        (is (= before (slurp (str (:file store)))))
        (s/select-option! driver ".assembly__scheme select" "No override")
        (is (s/wait-until #(nil? (get-in @state [:draft :scheme]))))
        (s/click! driver ".assembly__save button")
        (is (s/wait-until #(= 2 (count (:loadouts (loadouts/snapshot! store))))))
        (let [records (:loadouts (loadouts/snapshot! (loadouts/open! (:file store))))]
          (is (= (assoc source :loadout/scheme blue-id) (get records ship-id)))
          (is (nil? (:loadout/scheme (get records (get-in @state [:draft :loadout-id])))))))
      (loadouts/put! store (assoc source :loadout/scheme (random-uuid)) :update)
      (workspace/switch! driver "ships")
      (s/click! driver (str card " .ship-card__load"))
      (s/wait-visible! driver ".ship-inspector [role=alert]")
      (is (s/wait-until #(= "9aa4af" (:color (material-test/slot driver [])))))
      (finally (s/quit! driver) (fixture/stop! started)))))
