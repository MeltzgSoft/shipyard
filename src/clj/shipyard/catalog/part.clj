(ns shipyard.catalog.part
  "Pure transformations over catalog part data.")

(defn durable-mounts
  "Remove database-only identifiers before mounts leave the catalog boundary."
  [mounts]
  (mapv #(dissoc % :db/id) mounts))
