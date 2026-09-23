(ns shipyard.integration.scheme-store-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.scheme.db :as db]
            [shipyard.persistence-fixture :as persisted]))

(deftest normalized-scheme-roundtrip
  (let [started (fixture/start!) facade (:shipyard.scheme/db (:system started))
        material {:base [0.1 0.2 0.3] :metalness 0.7 :roughness 0.3 :paint "Example"}
        part (:weapon fixture/ids) path [[:weapon 0]]
        record {:scheme/id (random-uuid) :scheme/name "Scheme" :scheme/roles {:hull material}
                :scheme/layers {"Primary" material} :scheme/layer-ids? true
                :scheme/instances {path {:part-id part :material material}}
                :scheme/groups [{:group/id (random-uuid) :group/name "Group" :group/order 0
                                 :group/members [{:path path :part-id part} {:path [] :part-id (:hull fixture/ids)}] :group/material material}]
                :scheme/details {path {:part-id part :mesh-key (apply str (repeat 64 "a"))
                                       :faces {(apply str (repeat 72 "0")) (dissoc material :paint)}}}}]
    (try
      (is (= record (:scheme (db/put! facade record :create))))
      (is (= record (get-in (persisted/records! facade :schemes) [:schemes (:scheme/id record)])))
      (let [updated (assoc record :scheme/name "Changed" :scheme/groups [] :scheme/instances {} :scheme/details {})]
        (is (= updated (:scheme (db/put! facade updated :update))))
        (is (= updated (get-in (persisted/records! facade :schemes) [:schemes (:scheme/id record)]))))
      (db/delete! facade (:scheme/id record))
      (is (empty? (:schemes (persisted/records! facade :schemes))))
      (finally (fixture/stop! started)))))
