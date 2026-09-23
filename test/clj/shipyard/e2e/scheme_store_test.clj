(ns shipyard.e2e.scheme-store-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.scheme.db :as db]
            [shipyard.scheme.transforms-test :refer [record group-record]]))

(deftest assembly-work-preserves-durable-schemes
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver)
        store (:shipyard.scheme/db (:system started))
        record (assoc record :scheme/groups [group-record])]
    (try
      (is (= record (:scheme (db/put! store record :create))))
      (let [before (db/snapshot! store)]
        (s/go! driver (s/base-url (:system started)))
        (s/wait-visible! driver "#library-results .part")
        (s/click! driver ".masthead__mode:has-text('Assemble')")
        (s/wait-visible! driver ".assembly__hull")
        (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
        (s/click! driver ".assembly__hull button")
        (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
        (is (= before (db/snapshot! store)))
        (is (= record (get-in (persisted/records! store :schemes) [:schemes (:scheme/id record)]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
