(ns shipyard.e2e.projection-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.part-regions-test :as regions])
  (:import [com.microsoft.playwright Page Request]
           [java.util.function Consumer]))

(deftest missing-numeric-baseline-recovers-the-authoritative-snapshot
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        recoveries (atom 0)
        saved #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (.onRequest ^Page (:page driver)
                  (reify Consumer
                    (accept [_ request]
                      (when (.contains (.url ^Request request) "/parts/regions/snapshot")
                        (swap! recoveries inc)))))
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "weapon") (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (s/input! driver "#region-stroke input[name=radius]" "2" "input")
      (apply brush/stroke! driver (regions/region-point driver 0))
      (is (s/wait-until #(= 1 (:revision (saved)))))
      (s/open-part! driver "weapon-alt") (s/await-part driver (:weapon-alt fixture/ids))
      (s/open-part! driver "weapon") (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (is (s/wait-until #(= "true" (s/js driver "() => document.querySelector('#part-regions').getAttribute('data-region-projection-ready')"))))
      (is (s/js driver "() => !document.querySelector('#part-regions').hasAttribute('data-region-faces')"))
      (s/input! driver "#region-stroke input[name=radius]" "2" "input")
      ;; Lose the preserved numeric baseline after local preview has finished,
      ;; immediately before applying the saved delta. A later layer form can
      ;; legitimately return a full projection when its face revision is stale.
      (s/js driver "() => { const loseBaseline = e => { if (e.detail.xhr.responseURL.includes('/parts/regions/stroke')) { document.querySelector('#region-snapshot').shipyardRegions = null; document.body.removeEventListener('htmx:beforeSwap', loseBaseline); } }; document.body.addEventListener('htmx:beforeSwap', loseBaseline); }")
      (apply brush/stroke! driver (regions/region-point driver 1))
      (is (s/wait-until #(= 2 (:revision (saved)))))
      (is (= 2 (:revision (saved))))
      ;; Playwright's synchronous API dispatches request callbacks while an API
      ;; call is active, so each poll must also communicate with the browser.
      (is (s/wait-until #(do (s/stats driver) (pos? @recoveries)))
          (s/js driver "() => ({status:document.querySelector('#region-status').textContent,meta:document.querySelector('#part-regions').dataset.regions,delta:document.querySelector('#part-regions').dataset.regionDelta,cached:!!document.querySelector('#region-snapshot').shipyardRegions})"))
      (is (s/wait-until #(= "true" (s/js driver "() => document.querySelector('#part-regions').getAttribute('data-region-projection-ready')"))))
      (is (= 2 (count (:faces (saved)))))
      (is (s/wait-until #(= 2 (count (set (vals (regions/region-colors driver))))))
          "Recovered numeric masks produce the saved viewport colors")
      (finally (s/quit! driver) (fixture/stop! started)))))
