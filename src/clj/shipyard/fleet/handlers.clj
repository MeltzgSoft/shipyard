(ns shipyard.fleet.handlers
  "Fleet Browser admission and server-owned table/editor navigation."
  (:require [shipyard.fleet.db :as fleet]
            [shipyard.fleet.plan :as plan]
            [shipyard.fleet.views :as views]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.views :as shell]
            [shipyard.workspace.db :as workspace]))

(defn facade [{:keys [fleets catalog]}]
  (or fleets {:store (:store catalog) :catalog catalog}))

(defn- entries! [deps]
  (let [db (facade deps)]
    (->> (:fleets (fleet/snapshot! db))
         (vals)
         (sort-by (juxt :fleet/name #(str (:fleet/id %))))
         (mapv (fn [record] {:fleet record :plan (plan/print-plan (fleet/export-snapshot! db (:fleet/id record)))})))))

(defn current! [{:keys [workspace] :as deps} {:keys [params headers]}]
  (let [{:keys [view selection filters]} (workspace/workspace! workspace :fleets)
        record (when selection (fleet/record! (facade deps) selection))]
    (cond
      (= "fleet-results" (get headers "hx-target"))
      (do (workspace/remember! workspace :fleets params)
          (htmx/fragment (views/results (entries! deps) (:filters (workspace/workspace! workspace :fleets)))))
      (and (= view :editor) record)
      (htmx/fragment (views/editor record (get params "error")))
      :else
      (do (when (= view :editor) (workspace/update-workspace! workspace :fleets assoc :view :table :selection nil))
          (htmx/fragment (list (shell/detail-empty)
                               (views/table (entries! deps) filters (or (get params "error")
                                                                        (when selection "This fleet is unavailable in the selected library."))))
                         {:events {:clear nil}})))))

(defn open! [{:keys [workspace] :as deps} {:keys [params]}]
  (workspace/remember! workspace :fleets params)
  (if-let [record (fleet/record! (facade deps) (parse-uuid (get params "id")))]
    (do (workspace/update-workspace! workspace :fleets assoc :view :editor :selection (:fleet/id record))
        (current! deps {}))
    (current! deps {:params {"error" "This fleet is unavailable in the selected library."}})))

(defn create! [{:keys [workspace] :as deps} {:keys [params]}]
  (workspace/remember! workspace :fleets params)
  (let [result (fleet/create! (facade deps) (get params "name"))]
    (if (:error result)
      (assoc (current! deps {:params {"error" (or (:message result) "Could not create this fleet. Enter a name and select a library.")}}) :status 422)
      (let [record (:fleet result)]
        (workspace/update-workspace! workspace :fleets assoc :view :editor :selection (:fleet/id record))
        (current! deps {})))))
