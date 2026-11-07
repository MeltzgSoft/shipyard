(ns shipyard.e2e.mount-color-defaults-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.loadout.db :as loadouts])
  (:import [com.microsoft.playwright Page]))

(defn- expect-colors! [driver enabled?]
  (is (s/wait-until #(= enabled? (:mount-colors-enabled (s/stats driver)))))
  (is (= (str enabled?) (s/js driver "() => document.querySelector('[data-mount-colors-toggle]').getAttribute('aria-pressed')"))))

(defn- tab! [driver tab label]
  (s/click! driver (str "nav[aria-label='Ship editor'] button:text-is('" label "')"))
  (s/wait-visible! driver (str "[data-ship-inspector-tab='" tab "']")))

(deftest assembly-defaults-on-and-each-tab-retains-its-own-display-choice
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        store (:shipyard.loadout/db sys)
        record {:loadout/id (random-uuid) :loadout/name "Colors cruiser" :loadout/hull (:hull fixture/ids)
                :loadout/slots {[[:weapon 0]] (:weapon fixture/ids)}}]
    (try
      (loadouts/put! store record :create)
      (loadouts/put! store (assoc record :loadout/id (random-uuid) :loadout/name "Other cruiser") :create)
      (s/go! driver (s/base-url sys))
      (s/open-class! driver "Colors cruiser")
      (s/await-assembly-prepared! driver 2)
      (expect-colors! driver true)
      (is (pos? (:visible-mount-markers (s/stats driver))))
      (is (> (count (set (:materials (s/stats driver)))) 1))
      (tab! driver "schemes" "Schemes")
      (expect-colors! driver false)
      (is (zero? (:visible-mount-markers (s/stats driver))))
      (s/click! driver "[data-mount-colors-toggle]")
      (expect-colors! driver true)
      (tab! driver "paint" "Customize")
      (expect-colors! driver false)
      (tab! driver "assembly" "Assembly")
      (expect-colors! driver true)
      (s/click! driver "[data-mount-colors-toggle]")
      (expect-colors! driver false)
      (tab! driver "schemes" "Schemes")
      (expect-colors! driver true)
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#settings")
      (s/click! driver "[data-workspace-mode=ships]")
      (s/wait-visible! driver "[data-ship-inspector-tab=schemes]")
      (expect-colors! driver true)
      (.reload ^Page (:page driver))
      (s/wait-visible! driver "[data-ship-inspector-tab=schemes]")
      (expect-colors! driver true)
      (tab! driver "assembly" "Assembly")
      (expect-colors! driver false)
      (s/ship-table! driver)
      (s/open-class! driver "Other cruiser")
      (s/await-assembly-prepared! driver 2)
      (expect-colors! driver false)
      (tab! driver "paint" "Customize")
      (expect-colors! driver false)
      (finally (s/quit! driver) (fixture/stop! started)))))
