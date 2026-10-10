(ns shipyard.e2e.glow-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.detail-brush-test :as brush]))

(deftest glow-illuminates-nearby-unpainted-surfaces
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (lf/save-class! sys)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly") (workspace/await-ship! driver)
      (s/click! driver "button:text-is('Create named ship')")
      (s/fill-and-blur! driver "#paint-create input[name=name]" "Glow proof")
      (s/click! driver "#paint-create button")
      (s/wait-visible! driver "#paint-brush") (workspace/await-ship! driver)
      (s/input! driver "#paint-brush input[name=brush-color]" "#ff0000" "input")
      (s/input! driver "#paint-brush input[name=radius]" "10" "input")
      (let [source (first (filter :front? (:face-centers (s/slot driver [["weapon" 0]]))))
            ;; Sample the hull next to the small emitter, not distant triangle
            ;; centroids on the oversized fixture hull or the emitter itself.
            points (for [dx [45 65 85] dy [-35 0 35]] {:x (+ (:x source) dx) :y (+ (:y source) dy)})
            sample (fn [{:keys [x y]}] (s/js driver (str "() => window.__shipyard.lightingPixel(" x "," y ")")))
            before (mapv sample points)]
        (s/input! driver "#paint-brush input[name=brush-glow]" "1" "input")
        (apply brush/stroke! driver (brush/face-point driver [["weapon" 0]] 2))
        (brush/await-saved! driver)
        (is (s/wait-until #(true? (get-in (s/stats driver) [:glow :active?]))))
        (is (= true (s/js driver "() => Object.values(window.__shipyard.stats().assembly.slots).some(s => s.emissionPrepared)"))
            "Lighting consumes a backend-prepared surface summary")
        (let [after (mapv sample points)]
          (is (some true? (map (fn [a b] (> (- (first b) (first a)) 0.01)) before after))
              (pr-str {:before before :after after})))
        (is (zero? (:glow (s/slot driver []))) "Receiver has no emissive paint")
        (s/screenshot-el! driver "#viewport" (java.io.File. "/tmp/shipyard-local-glow.png"))
        (s/click! driver "#paint-brush button[value=undo]")
        (brush/await-saved! driver)
        (is (s/wait-until #(false? (get-in (s/stats driver) [:glow :active?])))))
      (finally (s/quit! driver) (fixture/stop! started)))))
