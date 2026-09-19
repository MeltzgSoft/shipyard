(ns shipyard.e2e.paint-material-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]
            [shipyard.scheme.db :as schemes]
            [shipyard.scheme.material :as material]))

(def red (assoc material/neutral :base [1 0 0] :metalness 0.7 :roughness 0.2))
(def blue (assoc material/neutral :base [0 0 1] :metalness 0.1 :roughness 0.9))
(defn slot [driver path]
  (first (filter #(= path (:slot %)) (get-in (s/stats driver) [:assembly :slots]))))

(deftest repeated-instance-paint-and-overlay-restoration
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        id (random-uuid) ship-id (random-uuid)
        scheme {:scheme/id id :scheme/name "Paint proof" :scheme/roles {:weapon red}
                :scheme/instances {[[:weapon 1]] {:part-id (:weapon fixture/ids) :material blue}
                                   [[:weapon 0] [:turret 0]] {:part-id (:turret fixture/ids) :material blue}}}
        ship {:loadout/id ship-id :loadout/name "Painted ship" :loadout/hull (:hull fixture/ids)
              :loadout/slots lf/assignments :loadout/scheme id}]
    (try
      (schemes/put! (:shipyard.scheme/db sys) scheme :create)
      (loadouts/put! (:shipyard.loadout/db sys) ship :create)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "ships")
      (s/click! driver ".ship-card__load")
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
      (is (empty? (get-in (s/stats driver) [:assembly :slots])))
      (workspace/switch! driver "ships")
      (workspace/await-ship! driver)
      (is (= "0000ff" (:color (slot driver [["weapon" 1]]))))
      (schemes/put! (:shipyard.scheme/db sys) (assoc-in scheme [:scheme/instances [[:weapon 1]] :material] red) :update)
      (workspace/switch! driver "browse")
      (workspace/switch! driver "ships")
      (workspace/await-ship! driver)
      (is (= "ff0000" (:color (slot driver [["weapon" 1]]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
