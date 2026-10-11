(ns shipyard.e2e.brush-regression-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.part-regions-test :as regions]
            [shipyard.e2e.paint-preparation-test :as preparation]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as classes]
            [shipyard.scheme.db :as schemes])
  (:import [com.microsoft.playwright Page]))

(defn- count-readbacks! [driver]
  (s/js driver "() => {window.brushReadbacks=0;const p=WebGL2RenderingContext.prototype,read=p.readPixels;p.readPixels=function(...args){window.brushReadbacks++;return read.apply(this,args)}}"))

(deftest region-refreshes-preserve-brightness-and-picking
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "weapon") (s/await-part driver (:weapon fixture/ids))
      (s/click! driver "[data-detail-tab=regions]")
      (s/input! driver "#region-stroke input[name=radius]" "2" "input")
      (count-readbacks! driver)
      (apply brush/stroke! driver (regions/region-point driver 0))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (= ["ffffff"] (:materials (s/stats driver))) "Face colors use a white material multiplier after save")
      (let [readbacks (s/js driver "() => window.brushReadbacks")]
        (apply brush/stroke! driver (regions/region-point driver 1))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (is (= readbacks (s/js driver "() => window.brushReadbacks")))
        (is (= ["ffffff"] (:materials (s/stats driver))))
        (s/click! driver "[data-detail-tab=part]")
        (s/click! driver "[data-detail-tab=regions]")
        (is (= ["ffffff"] (:materials (s/stats driver))) "Returning to Regions reuses colors without darkening"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest detail-refreshes-preserve-untouched-parts-and-reuse-picking
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        class {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}
        palette {:scheme/id (random-uuid) :scheme/name "Fleet"
                 :scheme/layers {"Primary" {:base [0.3 0.5 0.7] :metalness 0 :roughness 0.8 :glow 0}}}]
    (try
      (classes/put! (:shipyard.loadout/db sys) class :create)
      (schemes/put! (:shipyard.scheme/db sys) palette :create)
      (s/go! driver (s/base-url sys)) (s/open-class! driver "Cruiser")
      (named/tab! driver "Customize")
      (s/select-option! driver "#paint-create select[name=scheme]" "Fleet")
      (named/create! driver "Brush proof")
      (s/await-assembly-prepared! driver 13)
      (s/input! driver "#paint-brush input[name=radius]" "2" "input")
      (count-readbacks! driver)
      (let [other (s/slot driver [["weapon" 1]])]
        (apply brush/stroke! driver (brush/face-point driver [] 0))
        (brush/await-saved! driver)
        (is (= "ffffff" (:color (s/slot driver []))))
        (testing "A save leaves untouched instances' appearance unchanged"
          (is (= (:color other) (:color (s/slot driver [["weapon" 1]]))))
          (is (= (:projection-sample other) (:projection-sample (s/slot driver [["weapon" 1]])))))
        (apply brush/stroke! driver (brush/face-point driver [["weapon" 0]] 2))
        (brush/await-saved! driver)
        (is (= "ffffff" (:color (s/slot driver []))) "A stroke on another part preserves the painted hull's brightness")
        (let [readbacks (s/js driver "() => window.brushReadbacks")]
          (apply brush/stroke! driver (brush/face-point driver [] 1))
          (brush/await-saved! driver)
          (is (= readbacks (s/js driver "() => window.brushReadbacks")) "Warm strokes reuse the depth-tested readback")
          (is (= "ffffff" (:color (s/slot driver []))))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest detailed-warm-strokes-avoid-full-material-preparation
  (s/assert-bundle!)
  (let [started (fixture/start! true preparation/detailed-library!) sys (:system started) driver (preparation/profile-driver!)
        class {:loadout/id (random-uuid) :loadout/name "Detailed cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}]
    (try
      (classes/put! (:shipyard.loadout/db sys) class :create)
      (s/go! driver (s/base-url sys)) (s/open-class! driver "Detailed cruiser")
      (named/tab! driver "Customize") (named/create! driver "Warm brush proof")
      (s/await-assembly-prepared! driver 13)
      (s/input! driver "#paint-brush input[name=radius]" "2" "input")
      (let [center (s/js driver "() => {const r=document.querySelector('canvas').getBoundingClientRect(),i=document.querySelector('.ship-inspector');return [r.x+(r.width-(i?28+i.offsetWidth:0))/2,r.y+r.height/2]}")]
        (apply brush/stroke! driver center) (brush/await-saved! driver)
        (is (s/wait-until #(not (:paint-preparing (s/slot driver [])))))
        (let [before (s/slot driver [])]
          (s/input! driver "#paint-brush input[name=brush-color]" "#00ff00" "input")
          (let [[x y] center mouse (.mouse ^Page (:page driver)) began (System/nanoTime)]
            (.move mouse x y) (.down mouse)
            (let [live (s/slot driver [])]
              (is (false? (:paint-preparing live)) "A tiny warm stroke renders immediately")
              (is (false? (get-in live [:paint-preparation :full])))
              (is (< (get-in live [:paint-preparation :triangles]) 100))
              (.up mouse) (brush/await-saved! driver)
              (is (s/wait-until #(not (:paint-preparing (s/slot driver [])))))
              (let [after (s/slot driver [])]
                (is (= (:uuid before) (:uuid after)))
                (is (false? (get-in after [:paint-preparation :full])))
                (is (< (get-in after [:paint-preparation :triangles]) 100) "A tiny warm stroke does not rewrite the 36,480-triangle hull")
                (is (not= (:details before) (:details after)))
                (spit "/tmp/shipyard-warm-brush-profile.edn"
                      (pr-str {:triangles 36480 :before (:paint-preparation before) :live (:paint-preparation live)
                               :after (:paint-preparation after) :stroke-ms (/ (- (System/nanoTime) began) 1e6)})))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
