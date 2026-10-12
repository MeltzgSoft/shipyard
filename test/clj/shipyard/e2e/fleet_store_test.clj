(ns shipyard.e2e.fleet-store-test
  "Fleet lists survive transient work in the existing browser workspaces."
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.fleet.db :as fleet]
            [shipyard.persistence-fixture :as persisted]))

(deftest assembly-work-keeps-fleet-lists-durable
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver)
        sys (:system started) facade {:store (:shipyard.store/db sys) :catalog (:shipyard.catalog/db sys)}]
    (try
      (let [list (:fleet (fleet/create! facade "Printing"))
            before (fleet/snapshot! facade)]
        (s/go! driver (s/base-url sys))
        (s/wait-visible! driver "#bulk-orient-results .bulk-orient__row")
        (s/open-assembly! driver)
        (s/wait-visible! driver ".assembly__hull")
        (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
        (s/click! driver ".assembly__hull button")
        (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
        (is (= before (fleet/snapshot! facade)))
        (is (= list (get-in (persisted/records! facade :fleets) [:fleets (:fleet/id list)]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
