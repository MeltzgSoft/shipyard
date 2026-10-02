(ns shipyard.vocabulary.handlers
  (:require [shipyard.http.htmx :as htmx]
            [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.bulk-orientation.views :as bulk-views]
            [shipyard.importer.db :as importer]
            [shipyard.workspace.db :as workspace]
            [shipyard.vocabulary.db :as db]
            [shipyard.vocabulary.transforms :as t]
            [shipyard.vocabulary.views :as views]))

(defn add! [{:keys [catalog workspace] :as deps} {:keys [params]}]
  (let [entry (t/entry (get params "field") (get params "value"))]
    (if-let [error (:error entry)]
      (htmx/fragment [:span.detail__error error] {:status 422})
      (do (db/add! (:store catalog) entry)
          (let [facets (bulk/facets! (importer/effective! deps))]
            (htmx/fragment (list [:span (str "Added “" (:value entry) "”. Available throughout Shipyard.")]
                                 (into [:div#classification-values {:hx-swap-oob "outerHTML"}]
                                       (rest (views/choices (:values facets))))
                                 (bulk-views/filter-updates facets (:filters (workspace/workspace! workspace :browse))))))))))
