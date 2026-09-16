(ns shipyard.e2e.loadout-store-test
  "The store foundation must start with the real app and survive transient UI work."
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.loadout.db :as db]
            [shipyard.loadout.transforms-test :refer [record]]))

(deftest transient-assembly-does-not-write-loadouts
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver)
        store (:shipyard.loadout/db (:system started))]
    (try
      (db/put! store record :create)
      (let [before (slurp (str (:file store)))]
        (s/go! driver (s/base-url (:system started)))
        (s/wait-visible! driver "#library-results .part")
        (s/click! driver ".masthead a:has-text('Assemble')")
        (s/wait-visible! driver ".assembly__hull")
        (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
        (s/click! driver ".assembly__hull button")
        (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
        (is (= before (slurp (str (:file store)))))
        (is (= {(:loadout/id record) record} (:loadouts (db/snapshot! (db/open! (:file store)))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
