(ns shipyard.e2e.paint-material-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]
            [shipyard.ship.db :as ships]
            [shipyard.paint.job :as job]
            [shipyard.scheme.material :as material]))

(def red (assoc material/neutral :base [1 0 0] :metalness 0.7 :roughness 0.2))
(def blue (assoc material/neutral :base [0 0 1] :metalness 0.1 :roughness 0.9))
(defn slot [driver path]
  (first (filter #(= path (:slot %)) (get-in (s/stats driver) [:assembly :slots]))))

(deftest repeated-instance-paint-and-overlay-restoration
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        id (random-uuid) ship-id (random-uuid) vessel-id (random-uuid)
        scheme {:scheme/id id :scheme/name "Paint proof" :scheme/roles {:weapon blue}
                :scheme/groups [{:group/id (random-uuid) :group/name "Battery" :group/order 0
                                 :group/members [{:path [[:weapon 0]] :part-id (:weapon fixture/ids)}
                                                 {:path [[:weapon 1]] :part-id (:weapon fixture/ids)}]
                                 :group/material red}]
                :scheme/instances {[[:weapon 1]] {:part-id (:weapon fixture/ids) :material blue}
                                   [[:weapon 0] [:turret 0]] {:part-id (:turret fixture/ids) :material blue}}}
        ship {:loadout/id ship-id :loadout/name "Painted ship" :loadout/hull (:hull fixture/ids)
              :loadout/slots lf/assignments :loadout/scheme id}]
    (try
      (loadouts/put! (:shipyard.loadout/db sys) (dissoc ship :loadout/scheme) :create)
      (ships/put! (:shipyard.ship/db sys) {:ship/id vessel-id :ship/name "Painted vessel" :ship/class ship-id :ship/paint (job/legacy scheme)} :create)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "ships")

      (s/open-named-ship! driver vessel-id)
      (workspace/await-ship! driver)
      (is (= "ff0000" (:color (slot driver [["weapon" 0]]))))
      (is (= "0000ff" (:color (slot driver [["weapon" 1]]))))
      (is (= "0000ff" (:color (slot driver [["weapon" 0] ["turret" 0]]))))
      (is (= 0.7 (:metalness (slot driver [["weapon" 0]]))))
      (is (= 0.9 (:roughness (slot driver [["weapon" 1]]))))
      (is (= "9aa4af" (:color (slot driver []))))
      (let [before (slot driver [["weapon" 1]])]
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(not= "0000ff" (:color (slot driver [["weapon" 1]])))))
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(= "0000ff" (:color (slot driver [["weapon" 1]])))))
        (is (= (:uuid before) (:uuid (slot driver [["weapon" 1]]))))
        ;; Mount markers allocate their GPU buffers on their first visible frame.
        (let [geometry-count (:geometries (s/stats driver))]
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(true? (:mount-colors-enabled (s/stats driver)))))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
          (is (= geometry-count (:geometries (s/stats driver))))))
      (workspace/switch! driver "assembly")
      (workspace/await-ship! driver)
      (is (s/wait-until #(= "9aa4af" (:color (slot driver [["weapon" 1]])))))
      (s/click! driver ".ship-inspector nav button:text-is('Paint')")
      (workspace/await-ship! driver)
      (is (= "0000ff" (:color (slot driver [["weapon" 1]]))))
      (ships/put! (:shipyard.ship/db sys) {:ship/id vessel-id :ship/name "Painted vessel" :ship/class ship-id
                                           :ship/paint (job/legacy (assoc-in scheme [:scheme/instances [[:weapon 1]] :material] red))} :update)
      (workspace/switch! driver "browse")
      (workspace/switch! driver "ships")
      (workspace/await-ship! driver)
      (is (= "ff0000" (:color (slot driver [["weapon" 1]]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
