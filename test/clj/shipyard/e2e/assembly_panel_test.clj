(ns shipyard.e2e.assembly-panel-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.loadout.db :as loadouts]
            [shipyard.loadout-fixture :as lf])
  (:import [com.microsoft.playwright Page Route]
           [java.util.function Consumer]))

(defn- await-ready! [driver]
  (s/wait-until #(zero? (s/count-els driver ".assembly__preparation"))))

(defn- slot-selector [path]
  (str ".assembly__slot[data-slot='" (pr-str path) "']"))

(defn- assign! [driver path label]
  (await-ready! driver)
  (let [selector (slot-selector path)]
    (when-not (s/js driver (str "() => document.querySelector(" (pr-str selector) ").open"))
      (s/click! driver (str selector " > summary")))
    (s/click! driver (str selector " button[name=part-id]:has(.assembly__candidate-name:text-is('" label "'))"))
    (s/wait-until #(str/includes? (s/text driver (str selector " > summary .assembly__mount-state")) label))))

(defn- clear! [driver path]
  (let [selector (str ".assembly__slot-wrap:has(> " (slot-selector path) ")")]
    (s/click! driver (str selector " > .assembly__mount-actions button"))))

(defn- workflow! [blocked?]
  (let [started (fixture/start! true)
        driver (s/make-driver)]
    (try
      (when blocked?
        (.route ^Page (:page driver) "**/js/viewport.js"
                (reify Consumer (accept [_ route] (.abort ^Route route)))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#library-results .part")
      (s/js driver "() => { window.assemblyCanvas = document.querySelector('#viewport'); }")
      (s/click! driver ".masthead a:has-text('Assemble')")
      (s/wait-visible! driver ".assembly__hull")
      (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
      (s/click! driver ".assembly__hull button")
      (s/wait-visible! driver (slot-selector [[:weapon 0]]))
      (testing "capacity-two choices and nested turrets are server rendered"
        (assign! driver [[:weapon 0]] "weapon")
        (assign! driver [[:weapon 1]] "weapon")
        (s/wait-visible! driver (slot-selector [[:weapon 0] [:turret 0]]))
        (assign! driver [[:weapon 0] [:turret 0]] "turret")
        (is (= 2 (s/count-els driver (str (slot-selector [[:weapon 0]]) " > .assembly__candidate-form button[name=part-id]"))))
        (is (not (str/includes? (s/text driver (str (slot-selector [[:weapon 0]]) " > .assembly__candidate-form .assembly__candidates")) "hint")))
        (is (not (str/includes? (s/text driver (str (slot-selector [[:weapon 0]]) " > .assembly__candidate-form .assembly__candidates")) "supported"))))
      (testing "replace/clear remove descendants and preserve the canvas"
        (assign! driver [[:weapon 0]] "weapon-alt")
        (is (= "Empty" (s/text driver (str (slot-selector [[:weapon 0] [:turret 0]]) " > summary .assembly__mount-state"))))
        (clear! driver [[:weapon 0]])
        (is (s/wait-until #(zero? (s/count-els driver (slot-selector [[:weapon 0] [:turret 0]])))))
        (is (true? (s/js driver "() => window.assemblyCanvas === document.querySelector('#viewport')"))))
      (testing "stale revision shows a recoverable error in HTML"
        (await-ready! driver)
        (s/js driver "() => { document.querySelector('.assembly__hull input[name=revision]').value = '0'; }")
        (s/click! driver ".assembly__hull button")
        (is (s/wait-until #(str/includes? (s/text driver "#assembly") "draft changed"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest slot-panel-workflow
  (workflow! false))

(deftest slot-panel-without-viewport
  (workflow! true))

(deftest complete-synthetic-cruiser
  (let [started (fixture/start! true lf/scanned-library!) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#library-results .part")
      (s/click! driver ".masthead a:has-text('Assemble')")
      (s/wait-visible! driver ".assembly__hull")
      (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
      (s/click! driver ".assembly__hull button")
      (s/wait-visible! driver (slot-selector [[:weapon 0]]))
      (testing "a hull-only ship saves without losing the hull and can be completed later"
        (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
        (s/fill-and-blur! driver ".assembly__save input[name=name]" "Incomplete")
        (s/click! driver ".assembly__save button")
        (is (s/wait-until #(str/includes? (s/text driver "#library") "Ship saved.")))
        (is (= 1 (count (get-in (s/stats driver) [:assembly :slots]))))
        (let [store (:shipyard.loadout/db (:system started))
              records (:loadouts (loadouts/snapshot! (loadouts/open! (:file store))))
              saved (first (vals records))]
          (is (= 1 (count records)))
          (is (= "Incomplete" (:loadout/name saved)))
          (is (= (:hull fixture/ids) (:loadout/hull saved)))
          (is (= {} (:loadout/slots saved)))))
      (doseq [[path label] [[[[:prow 0]] "prow"] [[[:bridge 0]] "bridge"]
                            [[[:antenna 0]] "antenna"] [[[:antenna 1]] "antenna"]
                            [[[:weapon 0]] "weapon"] [[[:weapon 1]] "weapon"]
                            [[[:mirrored-weapon 0]] "weapon"] [[[:mirrored-weapon 1]] "weapon"]
                            [[[:weapon 0] [:turret 0]] "turret"] [[[:weapon 1] [:turret 0]] "turret"]
                            [[[:mirrored-weapon 0] [:turret 0]] "turret"]
                            [[[:mirrored-weapon 1] [:turret 0]] "turret"]]]
        (assign! driver path label))
      (is (s/wait-until #(= 13 (count (get-in (s/stats driver) [:assembly :slots])))))
      (testing "the complete hierarchy renders with distinct capacity and mirrored positions"
        (let [slots (get-in (s/stats driver) [:assembly :slots])
              weapons (filter #(= (:weapon lf/scanned-ids) (:part-id %)) slots)
              turrets (filter #(= (:turret lf/scanned-ids) (:part-id %)) slots)]
          (is (= 4 (count weapons)))
          (is (= 4 (count turrets)))
          (is (= 4 (count (set (map :matrix weapons)))))))
      (testing "saving with inferred and folder-derived roles persists the exact tree; repeated Save updates the same identity"
        (s/fill-and-blur! driver ".assembly__save input[name=name]" "Browser Cruiser")
        (s/click! driver ".assembly__save button")
        (is (s/wait-until #(str/includes? (s/text driver "#library") "Ship saved.")))
        (let [store (:shipyard.loadout/db (:system started))
              saved (first (vals (:loadouts (loadouts/snapshot! (loadouts/open! (:file store))))))]
          (is (= "Browser Cruiser" (:loadout/name saved)))
          (is (= lf/scanned-assignments (:loadout/slots saved)))
          (s/fill-and-blur! driver ".assembly__save input[name=name]" "Renamed Cruiser")
          (s/click! driver ".assembly__save button")
          (is (s/wait-until #(= "Renamed Cruiser" (get-in (loadouts/snapshot! store) [:loadouts (:loadout/id saved) :loadout/name]))))
          (is (= 1 (count (:loadouts (loadouts/snapshot! (loadouts/open! (:file store)))))))))
      (testing "alternative prow replacement removes its old object"
        (let [old (first (filter #(= (:prow fixture/ids) (:part-id %))
                                 (get-in (s/stats driver) [:assembly :slots])))]
          (assign! driver [[:prow 0]] "prow-alt")
          (is (s/wait-until
               #(let [slots (get-in (s/stats driver) [:assembly :slots])]
                  (and (= 13 (count slots))
                       (some (fn [item] (= (:prow-alt fixture/ids) (:part-id item))) slots)
                       (not-any? (fn [item] (= (:uuid old) (:uuid item))) slots)))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
