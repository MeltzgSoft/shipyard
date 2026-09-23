(ns shipyard.catalog.db-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.catalog.db :as db]))

(deftest immutable-catalog-projection
  (let [before (db/from-parts [{:part/id "a" :part/name "Hull" :part/bundle "Navy" :part/class "Cruiser"}
                               {:part/id "b" :part/name "Weapon" :part/present? false}])
        after (assoc-in before [:parts "a" :part/name] "New")]
    (is (= ["a"] (mapv :part/id (db/browse before {}))))
    (is (= "Hull" (:part/name (db/part before "a"))))
    (is (= "New" (:part/name (db/part after "a"))))
    (is (nil? (db/part before "b")))
    (is (= ["Navy"] (db/bundles before)))
    (is (= ["Cruiser"] (db/classes before)))))
