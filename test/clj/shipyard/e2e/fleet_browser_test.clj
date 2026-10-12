(ns shipyard.e2e.fleet-browser-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fleet.db :as fleet]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright Page]))

(deftest fleet-table-editor-and-workspace-round-trips
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver) ^Page page (:page driver)
        facade {:store (:shipyard.store/db sys) :catalog (:shipyard.catalog/db sys)}]
    (try
      (doseq [n (range 30)] (fleet/create! facade (format "Reserve %02d" n)))
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "#bulk-orient-results .bulk-orient__row")
      (s/click! driver "[data-workspace-mode=fleets]")
      (s/wait-visible! driver "#fleet-results tbody tr")
      (s/fill! driver "#fleet-filters input[name=q]" "Reserve")
      (is (s/wait-until #(= "Reserve" (s/js driver "() => document.getElementById('fleet-results').dataset.query"))))
      (s/wait-visible! driver "[aria-label='Open fleet Reserve 29']")
      (.scrollIntoViewIfNeeded (.locator page "[aria-label='Open fleet Reserve 29']"))
      (let [scroll (s/js driver "() => document.getElementById('fleet-results').scrollTop")]
        (is (pos? scroll))
        (.press (.locator page "[aria-label='Open fleet Reserve 29']") "Enter")
        (s/wait-visible! driver ".fleet-editor")
        (is (= "Reserve 29" (s/text driver ".fleet-editor h2")))
        (is (= (str (long scroll)) (get-in @(:state (:shipyard.workspace/db sys)) [:workspaces :fleets :filters "table-scroll"])))
        (s/click! driver "[data-workspace-mode=ships]")
        (s/wait-visible! driver "#ship-filters")
        (s/click! driver "[data-workspace-mode=browse]")
        (s/wait-visible! driver "#bulk-orient-results .bulk-orient__row")
        (s/click! driver "[data-workspace-mode=fleets]")
        (s/wait-visible! driver ".fleet-editor")
        (is (= "Reserve 29" (s/text driver ".fleet-editor h2")))
        (s/click! driver ".fleet-editor button:text-is('← Back to fleets')")
        (s/wait-visible! driver "#fleet-filters")
        (is (= "Reserve" (s/js driver "() => document.querySelector('#fleet-filters input').value")))
        (is (s/wait-until #(= scroll (s/js driver "() => document.getElementById('fleet-results').scrollTop"))))
        (.dblclick page "[aria-label='Open fleet Reserve 00']")
        (s/wait-visible! driver ".fleet-editor")
        (is (= "Reserve 00" (s/text driver ".fleet-editor h2"))))
      (s/click! driver ".fleet-editor button:text-is('← Back to fleets')")
      (s/wait-visible! driver "#fleet-filters")
      (s/fill! driver ".fleet-table header input[name=name]" "New fleet")
      (s/click! driver ".fleet-table header button:text-is('New fleet')")
      (s/wait-visible! driver ".fleet-editor")
      (is (= "New fleet" (s/text driver ".fleet-editor h2")))
      (is (= 31 (count (:fleets (fleet/snapshot! facade)))))
      (finally (s/quit! driver) (fixture/stop! started)))))
