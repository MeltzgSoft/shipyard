(ns shipyard.e2e.metallic-details-test
  (:require [shipyard.persistence-fixture :as persisted]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.paint-editor-test :as editor]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.scheme.db :as schemes])
  (:import [com.microsoft.playwright Page ConsoleMessage]
           [java.util.function Consumer]))

(defn finish [driver key]
  (first (filter #(= key (:key %)) (:face-finishes (materials/slot driver [])))))
(defn near? [a b] (and (number? b) (< (abs (- a b)) 0.00001)))

(deftest metallic-detail-rendering-and-history
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        store (:shipyard.scheme/db sys) errors (atom [])]
    (try
      (.onConsoleMessage ^Page (:page driver)
                         (reify Consumer
                           (accept [_ value]
                             (let [message (.text ^ConsoleMessage value)]
                               (when (re-find #"(?i)shader error|WebGLProgram|GL_INVALID|VALIDATE_STATUS" message)
                                 (swap! errors conj message))))))
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly") (workspace/await-ship! driver)
      (s/click! driver "button:text-is('Paint assembly')")
      (s/click! driver ".paint-scheme-actions summary:text-is('New')")
      (s/fill-and-blur! driver "#paint-create input" "Gold trim")
      (s/click! driver "#paint-create button")
      (s/wait-visible! driver "#paint-material") (workspace/await-ship! driver)
      (is (= "0.05" (s/js driver "() => document.querySelector('[name=brush-metalness]').value")))
      (editor/input! driver "#paint-material input[name=base]" "#123c8a" "input")
      (editor/input! driver "#paint-material input[name=metalness]" "0" "input")
      (editor/input! driver "#paint-material input[name=roughness]" "0.9" "input")
      (s/click! driver "#paint-material button:text-is('Save material')")
      (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status"))))
      (s/click! driver ".paint-tools button:text-is('Brush')")
      (s/wait-visible! driver "#paint-brush")
      (s/click! driver "#paint-brush input[name=cross-instances]")
      (editor/input! driver "#paint-brush input[name=radius]" "2" "input")
      (editor/input! driver "#paint-brush input[name=brush-color]" "#d4af37" "input")
      (editor/input! driver "#paint-brush input[name=brush-metalness]" "1" "input")
      (editor/input! driver "#paint-brush input[name=brush-roughness]" "0.15" "input")
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            front (vec (filter :front? (:face-centers (materials/slot driver []))))
            a (:key (front 0)) b (:key (front 1))
            durable #(get-in (schemes/snapshot! store) [:schemes id :scheme/details [] :faces])]
        (apply brush/stroke! driver (brush/face-point driver [] 0))
        (is (s/wait-until #(= "Details saved." (s/text driver "#brush-status"))))
        (is (= 1.0 (get-in (durable) [a :metalness])))
        (is (s/wait-until #(true? (:finish-compiled (materials/slot driver [])))))
        (is (near? 1 (:metalness (finish driver a))))
        (is (near? 0.15 (:roughness (finish driver a))))
        (is (near? 0 (:metalness (finish driver b))) "Unpainted face keeps matte base")
        (is (nil? (:details (materials/slot driver [["weapon" 0]]))))
        (let [geometries (:geometries (s/stats driver)) draws (:draws (s/stats driver))]
          (editor/input! driver "#paint-brush input[name=brush-metalness]" "0" "input")
          (editor/input! driver "#paint-brush input[name=brush-roughness]" "0.9" "input")
          (apply brush/stroke! driver (brush/face-point driver [] 1))
          (is (s/wait-until #(= 0.0 (get-in (durable) [b :metalness]))))
          (is (near? 0.9 (:roughness (finish driver b))))
          (is (= geometries (:geometries (s/stats driver))))
          (is (= draws (:draws (s/stats driver))))
          (is (near? 1 (:metalness (finish driver a)))))
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(false? (:vertex-colors (materials/slot driver [])))))
        (is (true? (:finish-enabled (materials/slot driver []))))
        (is (near? 1 (:metalness (finish driver a))))
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(true? (:vertex-colors (materials/slot driver [])))))
        (s/click! driver "#paint-brush input[name=mode][value=erase]")
        (apply brush/stroke! driver (brush/face-point driver [] 0))
        (is (s/wait-until #(nil? (get (durable) a))))
        (is (near? 0 (:metalness (finish driver a))))
        (is (near? 0.9 (:roughness (finish driver a))))
        (s/click! driver "#paint-brush button[value=undo]")
        (is (s/wait-until #(near? 1 (:metalness (finish driver a)))))
        (s/click! driver "#paint-brush button[value=redo]")
        (is (s/wait-until #(near? 0 (:metalness (finish driver a)))))
        (s/click! driver "#paint-brush button[value=undo]")
        (is (s/wait-until #(near? 1 (:metalness (finish driver a)))))
        (is (= (durable) (get-in (persisted/records! store :schemes) [:schemes id :scheme/details [] :faces])))
        (workspace/switch! driver "assembly") (workspace/switch! driver "paint")
        (workspace/await-ship! driver)
        (is (s/wait-until #(near? 1 (:metalness (finish driver a)))))
        (is (near? 0.9 (:roughness (finish driver b))))
        (is (empty? @errors) (pr-str @errors))
        (s/screenshot-el! driver "body" (fs/file "/tmp/shipyard-metallic-details.png")))
      (finally (s/quit! driver) (fixture/stop! started)))))
