(ns shipyard.e2e.picking-preparation-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.paint-preparation-test :as preparation]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]))

(deftest detailed-assembly-picking-reuses-source-geometries-and-keeps-instance-ranges
  (s/assert-bundle!)
  (let [started (fixture/start! true preparation/detailed-library!) sys (:system started) driver (preparation/profile-driver!)
        class {:loadout/id (random-uuid) :loadout/name "Picking cruiser" :loadout/hull (:hull fixture/ids) :loadout/slots lf/assignments}]
    (try
      (loadouts/put! (:shipyard.loadout/db sys) class :create)
      (s/go! driver (s/base-url sys))
      (s/open-class! driver "Picking cruiser")
      (s/await-assembly-prepared! driver 13)
      (s/click! driver "button:text-is('Create named ship')")
      (s/wait-visible! driver "#paint-create")
      (s/fill-and-blur! driver "#paint-create input[name=name]" "Picking proof")
      (s/click! driver "#paint-create button")
      (s/wait-visible! driver "#paint-brush")
      (is (s/wait-until #(every? (complement :paint-preparing) (get-in (s/stats driver) [:assembly :slots]))))
      (s/input! driver "#paint-brush input[name=radius]" "2" "input")
      (s/js driver "() => {window.addEventListener('pointerdown',()=>{const t=performance.now();setTimeout(()=>window.pickingFirstTurn=performance.now()-t,0)},{capture:true,once:true})}")
      (let [center! #(s/js driver "() => {const r=document.querySelector('canvas').getBoundingClientRect(),i=document.querySelector('.ship-inspector');return [r.x+(r.width-(i?28+i.offsetWidth:0))/2,r.y+r.height/2]}")
            [x y] (center!)]
        (apply brush/stroke! driver (center!))
        (brush/await-saved! driver)
        (let [cold (:picking (s/stats driver))]
          (is (= 2 (:geometries cold)) "One detailed hull plus one repeated small component source")
          (is (= 13 (:instances cold)))
          (is (zero? (:id-attribute-bytes cold)))
          (brush/right-stroke! driver x y)
          (brush/await-saved! driver)
          (let [warm (:picking (s/stats driver))]
            (is (= (:geometry-ids cold) (:geometry-ids warm)))
            (is (= (:position-bytes cold) (:position-bytes warm)))
            (spit "/tmp/shipyard-picking-profile.edn"
                  (pr-str {:triangles 36480 :instances 13
                           :browser (or (System/getProperty "shipyard.paint.profile.browser") "chromium")
                           :first-pointer-turn-ms (s/js driver "() => window.pickingFirstTurn")
                           :cold cold :warm warm}))))
        (workspace/switch! driver "browse")
        (workspace/switch! driver "ships")
        (s/await-assembly-prepared! driver 13)
        (apply brush/stroke! driver (center!))
        (brush/await-saved! driver)
        (is (= 2 (get-in (s/stats driver) [:picking :geometries]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
