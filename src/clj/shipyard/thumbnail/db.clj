(ns shipyard.thumbnail.db
  "Metadata-only assembly thumbnail stamps, with deferred coherent source reads."
  (:require [datalevin.core :as d]
            [shipyard.catalog.db :as catalog]
            [shipyard.store.db :as store]
            [shipyard.store.transforms :as t]))

(defn assembly-context!
  ([deps kind id] (assembly-context! deps kind id nil))
  ([{:keys [catalog]} kind id expected]
   (store/read!
    (:store catalog)
    (fn [db]
      (let [library (:library @(:state catalog))
            ship (when (= kind "ship")
                   (d/pull db [:ship/id :ship/name :ship/revision :ship/deleted?
                               {:ship/class [:loadout/id]
                                :ship/scheme [:scheme/id :scheme/revision :scheme/deleted?]}] [:ship/id id]))
            class-id (if (= kind "ship") (get-in ship [:ship/class :loadout/id]) id)
            class (when class-id (d/pull db store/loadout-pattern [:loadout/id class-id]))]
        (when (and (:loadout/id class) (not (:loadout/deleted? class))
                   (or (= kind "class") (and (:ship/id ship) (not (:ship/deleted? ship)))))
          (let [record (t/loadout-value class)
                ids (sort (distinct (cons (:loadout/hull record) (vals (:loadout/slots record)))))
                pattern catalog/attachment-pattern
                parts (keep #(let [part (d/pull db pattern [:part/key [library %]])]
                               (when (:part/present? part) (t/part-value part nil))) ids)
                stamp {:library library :class (select-keys class [:loadout/id :loadout/revision])
                       :ship (dissoc ship :ship/name)
                       :parts (mapv (fn [part]
                                      {:metadata (dissoc part :part/mounts)
                                       :regions (:stamp (catalog/thumbnail-context! catalog (:part/id part)))}) parts)}]
            (when (and expected (not= expected stamp))
              (throw (ex-info "Thumbnail source changed before preparation." {})))
            {:stamp stamp :record record :label (or (:ship/name ship) (:loadout/name record))
             :database (catalog/from-parts
                        (if expected
                          (keep #(-> (catalog/part-context! catalog %) :part) ids)
                          parts))
             :ship (when (and expected ship) (store/record-value db :ships id))
             :scheme (when (and expected (:ship/scheme ship))
                       (store/record-value db :schemes (get-in ship [:ship/scheme :scheme/id])))})))))))
