(ns shipyard.e2e.metallic-details-test
  (:require [shipyard.persistence-fixture :as persisted]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.ship.db :as schemes]
            [shipyard.e2e.named-ship-test :as named])
  (:import [com.microsoft.playwright Page ConsoleMessage]
           [java.util.function Consumer]))

(defn finish [driver key]
  (first (filter #(= key (:key %)) (:face-finishes (s/slot driver [])))))
(defn near? [a b] (and (number? b) (< (abs (- a b)) 0.00001)))

(deftest metallic-detail-rendering-and-history
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        store (:shipyard.ship/db sys) errors (atom [])]
    (try
      (.onConsoleMessage ^Page (:page driver)
                         (reify Consumer
                           (accept [_ value]
                             (let [message (.text ^ConsoleMessage value)]
                               (when (re-find #"(?i)shader error|WebGLProgram|GL_INVALID|VALIDATE_STATUS" message)
                                 (swap! errors conj message))))))
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (lf/save-class! sys)
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly") (workspace/await-ship! driver)
      (s/click! driver "button:text-is('Create named ship')")
      (s/fill-and-blur! driver "#paint-create input[name=name]" "Gold trim")
      (s/click! driver "#paint-create button")
      (s/wait-visible! driver "#paint-brush") (workspace/await-ship! driver)
      (is (= "0.05" (s/js driver "() => document.querySelector('[name=brush-metalness]').value")))
      (named/tab! driver "Schemes")
      (s/fill-and-blur! driver "#scheme-create input[name=name]" "Matte blue")
      (s/click! driver "#scheme-create button")
      (s/wait-visible! driver "#scheme-material")
      (s/input! driver "#scheme-material input[name=base]" "#123c8a" "input")
      (s/input! driver "#scheme-material input[name=metalness]" "0" "input")
      (s/input! driver "#scheme-material input[name=roughness]" "0.9" "input")
      (s/input! driver "#scheme-material input[name=glow]" "0.25" "input")
      (s/click! driver "#scheme-material button")
      (named/tab! driver "Customize")
      (s/select-option! driver "#paint-scheme select" "Matte blue")
      (is (s/wait-until #(= 0.25 (:glow (s/slot driver [])))))
      (s/input! driver "#paint-brush input[name=radius]" "2" "input")
      (s/input! driver "#paint-brush input[name=brush-color]" "#d4af37" "input")
      (s/input! driver "#paint-brush input[name=brush-metalness]" "1" "input")
      (s/input! driver "#paint-brush input[name=brush-roughness]" "0.15" "input")
      (s/input! driver "#paint-brush input[name=brush-glow]" "0.8" "input")
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :ship-id])
            front (vec (filter :front? (:face-centers (s/slot driver []))))
            a (:key (front 0)) b (:key (front 1))
            durable #(get-in (schemes/snapshot! store) [:ships id :ship/paint :paint/details [] :faces])]
        (apply brush/stroke! driver (brush/face-point driver [] 0))
        (is (s/wait-until #(= "Details saved." (s/text driver "#brush-status"))))
        (is (= 1.0 (get-in (durable) [a :metalness])))
        (is (= 0.8 (get-in (durable) [a :glow])))
        (is (s/wait-until #(true? (:finish-compiled (s/slot driver [])))))
        (is (near? 1 (:metalness (finish driver a))))
        (is (near? 0.15 (:roughness (finish driver a))))
        (is (near? 0.8 (:glow (finish driver a))))
        (is (near? 0.25 (:glow (finish driver b))))
        (is (near? 0 (:metalness (finish driver b))) "Unpainted face keeps matte base")
        (is (nil? (:details (s/slot driver [["weapon" 0]]))))
        (let [geometries (:geometries (s/stats driver)) draws (:draws (s/stats driver))]
          (s/input! driver "#paint-brush input[name=brush-metalness]" "0" "input")
          (s/input! driver "#paint-brush input[name=brush-roughness]" "0.9" "input")
          (s/input! driver "#paint-brush input[name=brush-glow]" "0" "input")
          (apply brush/stroke! driver (brush/face-point driver [] 1))
          (is (s/wait-until #(= 0.0 (get-in (durable) [b :metalness]))))
          (is (near? 0.9 (:roughness (finish driver b))))
          (is (near? 0 (:glow (finish driver b))))
          (is (= geometries (:geometries (s/stats driver))))
          (is (= draws (:draws (s/stats driver))))
          (is (near? 1 (:metalness (finish driver a)))))
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(false? (:vertex-colors (s/slot driver [])))))
        (is (near? 0 (:glow (s/slot driver []))) "Mount identification suppresses emission")
        (is (s/wait-until #(false? (get-in (s/stats driver) [:glow :active?]))))
        (is (true? (:finish-enabled (s/slot driver []))))
        (is (near? 1 (:metalness (finish driver a))))
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(true? (:vertex-colors (s/slot driver [])))))
        (is (s/wait-until #(true? (get-in (s/stats driver) [:glow :active?]))))
        (s/click! driver "#paint-brush input[name=mode][value=erase]")
        (apply brush/stroke! driver (brush/face-point driver [] 0))
        (is (s/wait-until #(nil? (get (durable) a))))
        (is (near? 0 (:metalness (finish driver a))))
        (is (near? 0.9 (:roughness (finish driver a))))
        (is (near? 0.25 (:glow (finish driver a))))
        (s/click! driver "#paint-brush button[value=undo]")
        (is (s/wait-until #(near? 1 (:metalness (finish driver a)))))
        (s/click! driver "#paint-brush button[value=redo]")
        (is (s/wait-until #(near? 0 (:metalness (finish driver a)))))
        (s/click! driver "#paint-brush button[value=undo]")
        (is (s/wait-until #(near? 1 (:metalness (finish driver a)))))
        (is (= (durable) (get-in (persisted/records! store :ships) [:ships id :ship/paint :paint/details [] :faces])))
        ;; Leaving Ships preserves Customize. Opening Assembly resumes the
        ;; class editor, whose scene intentionally omits named custom details.
        (workspace/switch! driver "browse") (workspace/switch! driver "ships")
        (workspace/await-ship! driver)
        (is (= "paint" (s/js driver "() => document.querySelector('[data-ship-inspector-tab]').dataset.shipInspectorTab")))
        (is (= (str id) (s/js driver "() => document.querySelector('#paint-reset').elements.id.value")))
        (is (s/wait-until #(near? 1 (:metalness (finish driver a))))
            (pr-str {:saved (get (durable) a) :restored (finish driver a)
                     :slot (dissoc (s/slot driver []) :face-centers :face-finishes)}))
        (is (near? 0.9 (:roughness (finish driver b))))
        (is (near? 0.8 (:glow (finish driver a))))
        (is (empty? @errors) (pr-str @errors))
        (s/screenshot-el! driver "body" (fs/file "/tmp/shipyard-metallic-details.png")))
      (finally (s/quit! driver) (fixture/stop! started)))))
