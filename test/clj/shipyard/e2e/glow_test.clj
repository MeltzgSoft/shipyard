(ns shipyard.e2e.glow-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.e2e.paint-editor-test :as editor]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.support :as s]))

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
      (s/wait-visible! driver "#paint-material") (workspace/await-ship! driver)
      (s/click! driver "#paint-target button[data-paint-target='[[:weapon 0]]']")
      (is (s/wait-until #(s/js driver "() => document.querySelector('#paint-material').elements.target.value === '[[:weapon 0]]'")))
      (editor/input! driver "#paint-material input[name=base]" "#ff0000" "input")
      (is (s/wait-until #(= "ff0000" (:color (materials/slot driver [["weapon" 0]])))))
      (let [source (first (filter :front? (:face-centers (materials/slot driver [["weapon" 0]]))))
            ;; Sample the hull next to the small emitter, not distant triangle
            ;; centroids on the oversized fixture hull or the emitter itself.
            points (for [dx [45 65 85] dy [-35 0 35]] {:x (+ (:x source) dx) :y (+ (:y source) dy)})
            sample (fn [{:keys [x y]}] (s/js driver (str "() => window.__shipyard.lightingPixel(" x "," y ")")))
            before (mapv sample points)]
        (editor/input! driver "#paint-material input[name=glow]" "1" "input")
        (is (s/wait-until #(true? (get-in (s/stats driver) [:glow :active?]))))
        (let [after (mapv sample points)]
          (is (some true? (map (fn [a b] (> (- (first b) (first a)) 0.01)) before after))
              (pr-str {:before before :after after})))
        (is (zero? (:glow (materials/slot driver []))) "Receiver has no emissive paint")
        (s/screenshot-el! driver "#viewport" (java.io.File. "/tmp/shipyard-local-glow.png"))
        (editor/input! driver "#paint-material input[name=glow]" "0" "input")
        (is (s/wait-until #(false? (get-in (s/stats driver) [:glow :active?])))))
      (finally (s/quit! driver) (fixture/stop! started)))))
