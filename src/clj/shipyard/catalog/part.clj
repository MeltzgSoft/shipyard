(ns shipyard.catalog.part
  "Pure transformations over catalog part data.")

(defn durable-mounts
  "Remove database-only identifiers before mounts leave the catalog boundary."
  [mounts]
  (mapv #(dissoc % :db/id) mounts))

(defn mount-summary [mounts]
  (reduce (fn [summary mount]
            (case (:mount/kind mount)
              :plug (update summary :plugs inc)
              :socket (update-in summary [:sockets (set (:mount/accepts mount))]
                                 (fnil + 0) (or (:mount/capacity mount) 1))
              summary))
          {:plugs 0 :sockets {}} mounts))
