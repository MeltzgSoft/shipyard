(ns shipyard.e2e.assembly-compatibility-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.compatibility-fixture :as compatibility]
            [shipyard.e2e.support :as s]
            [shipyard.loadout.db :as loadouts]
            [shipyard.persistence-fixture :as persisted])
  (:import [com.microsoft.playwright Page]))

(def toggle ".assembly__compatibility input[name=allow-other-factions]")
(defn- slot [path] (str ".assembly__slot[data-slot='" (pr-str path) "']"))
(defn- choice [path name] (str (slot path) " button[name=part-id]:has(.assembly__candidate-name:text-is('" name "'))"))
(defn- ready! [driver]
  (is (s/wait-until #(zero? (s/count-els driver ".assembly__preparation, #detail .htmx-request, #detail .htmx-swapping, #detail .htmx-settling")))))
;; The prior candidate can remain visible until HTMX swaps the toggle response.
(defn- set-compatibility! [{:keys [^Page page] :as driver} enabled?]
  (ready! driver)
  (.waitForResponse page "**/assembly/compatibility"
                    ^Runnable #(if enabled? (.check page toggle) (.uncheck page toggle)))
  (ready! driver))
(defn- assign! [driver path name]
  (ready! driver)
  (when-not (s/js driver (str "() => document.querySelector(" (pr-str (slot path)) ").open"))
    (s/click! driver (str (slot path) " > summary")))
  (s/click! driver (choice path name))
  (is (s/wait-until #(= name (s/text driver (str (slot path) " > summary .assembly__mount-state")))))
  (ready! driver))

(deftest cross-faction-assembly-saves-reopens-and-retains-universal-parts
  (s/assert-bundle!)
  (let [started (fixture/start! true compatibility/library! compatibility/author!)
        sys (:system started) database (:shipyard.loadout/db sys) driver (s/make-driver)
        state (:state (:shipyard.assembly/db sys))]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-assembly! driver)
      (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
      (s/click! driver ".assembly__hull button")
      (s/wait-visible! driver toggle)
      (ready! driver)
      (is (not (s/js driver "() => document.querySelector('.assembly__compatibility input[name=allow-other-factions]').checked")))
      (is (= 1 (s/count-els driver (choice [[:weapon 0]] "Universal Weapon"))))
      (is (zero? (s/count-els driver (choice [[:weapon 0]] "Foreign Weapon"))))
      (is (zero? (s/count-els driver (choice [[:weapon 0]] "Escort Weapon"))))
      (assign! driver [[:weapon 1]] "Universal Weapon")
      (s/fill-and-blur! driver ".assembly__save input[name=name]" "Mixed Cruiser")
      (set-compatibility! driver true)
      (s/wait-visible! driver (choice [[:weapon 0]] "Foreign Universal"))
      (is (= "Mixed Cruiser" (s/js driver "() => document.querySelector('.assembly__save input[name=name]').value")))
      (is (= 1 (s/count-els driver (choice [[:weapon 0]] "Foreign Weapon"))))
      (is (zero? (s/count-els driver (choice [[:weapon 0]] "Escort Weapon"))))
      (assign! driver [[:weapon 0]] "Foreign Universal")
      (assign! driver [[:weapon 0] [:turret 0]] "turret")
      (s/await-assembly-prepared! driver 4)
      (let [before (:draft @state) viewport (get-in (s/stats driver) [:assembly :slots])]
        (set-compatibility! driver false)
        (s/wait-visible! driver "[role=alert]:has-text('Clear parts from other factions')")
        (is (= before (:draft @state)))
        (is (s/js driver "() => document.querySelector('.assembly__compatibility input[name=allow-other-factions]').checked"))
        (is (= viewport (get-in (s/stats driver) [:assembly :slots])))
        (is (empty? (:loadouts (loadouts/snapshot! database)))))
      (s/click! driver ".assembly__save button")
      (s/wait-visible! driver "[role=status]:text-is('Class saved.')")
      (let [id (get-in @state [:draft :loadout-id]) card (str ".ship-card[data-loadout-id='" id "']")]
        (is (true? (:loadout/allow-other-factions? (get-in (persisted/records! database :loadouts) [:loadouts id]))))
        (s/ship-table! driver)
        (s/wait-visible! driver (str card " .ship-thumbnail img"))
        (is (not (str/includes? (s/text driver card) "Preview unavailable")))
        (s/open-class! driver "Mixed Cruiser")
        (s/await-assembly-prepared! driver 4)
        (is (s/js driver "() => document.querySelector('.assembly__compatibility input[name=allow-other-factions]').checked"))
        (s/ship-table! driver)
        (s/click! driver (str card " button:text-is('Duplicate')"))
        (s/wait-visible! driver ".assembly__save")
        (is (true? (get-in @state [:draft :allow-other-factions?])))
        (is (nil? (get-in @state [:draft :loadout-id])))
        (s/await-assembly-prepared! driver 4)
        (s/click! driver (str ".assembly__slot-wrap:has(> " (slot [[:weapon 0]]) ") > .assembly__mount-actions button"))
        (s/wait-visible! driver (str (slot [[:weapon 0]]) " .assembly__mount-state:text-is('Empty')"))
        (set-compatibility! driver false)
        (is (s/wait-until #(false? (get-in @state [:draft :allow-other-factions?]))))
        (s/wait-visible! driver (choice [[:weapon 0]] "Universal Weapon"))
        (is (s/wait-until #(zero? (s/count-els driver (choice [[:weapon 0]] "Foreign Universal")))))
        (s/await-assembly-prepared! driver 2)
        (ready! driver)
        (s/screenshot-el! driver "#assembly-rail" (java.io.File. "/tmp/shipyard-assembly-compatibility.png")))
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "[data-classification-field=class][data-classification-value=Universal]")
      (is (= "Built-in" (s/text driver "[data-classification-field=class][data-classification-value=Universal] td:last-child")))
      (is (zero? (s/count-els driver "[data-classification-field=class][data-classification-value=Universal] button")))
      (finally (s/quit! driver) (fixture/stop! started)))))
