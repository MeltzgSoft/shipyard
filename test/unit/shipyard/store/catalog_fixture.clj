(ns shipyard.store.catalog-fixture
  "Pure immutable catalog construction for domain tests."
  (:require [shipyard.catalog.db :as catalog]))

(defn empty-db [& _] (catalog/from-parts []))
(defn db-with [database parts]
  (catalog/from-parts
   (vals (reduce (fn [result part] (update result (:part/id part) merge part)) (:parts database) parts))))
