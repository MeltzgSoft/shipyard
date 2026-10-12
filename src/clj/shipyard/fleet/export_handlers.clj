(ns shipyard.fleet.export-handlers
  "Capture and publish one consistent fleet bundle through the shared store boundary."
  (:require [shipyard.fleet.db :as fleets]
            [shipyard.fleet.export :as export]
            [shipyard.fleet.handlers :as browser]
            [shipyard.fleet.export-views :as views]
            [shipyard.fleet.plan :as plan]
            [shipyard.fleet.editor-transforms :as editor]
            [shipyard.settings.db :as settings]
            [shipyard.store.db :as store]
            [shipyard.workspace.db :as workspace]))

(defn panel! [deps record result]
  (let [facade (browser/facade deps)]
    (views/controls record (settings/export-root! (:store facade))
                    (:ready? (plan/print-plan (fleets/export-snapshot! facade (:fleet/id record)))) result)))

(defn export! [{:keys [workspace] :as deps} {:keys [params] :as request}]
  (let [id (some-> (get params "id") (parse-uuid))
        facade (browser/facade deps)
        selected (:selection (workspace/workspace! workspace :fleets))]
    (if-not (editor/selected? selected params)
      (assoc (browser/current! deps {:params {"error" "This fleet selection changed. Reopen the fleet before exporting."}}) :status 409)
      (try
        ;; The shared lock spans snapshot capture and publication, preventing a
        ;; concurrent durable mutation from changing the bundle's references.
        (let [result (store/read! (:store facade)
                                  (fn [_]
                                    (let [snapshot (fleets/export-snapshot! facade id)
                                          destination (settings/export-root! (:store facade))]
                                      (when-not snapshot
                                        (throw (ex-info "This fleet is unavailable. Select a library and reopen the fleet." {})))
                                      (export/export! snapshot (get-in snapshot [:library :library/root]) destination))))]
          (browser/current! deps (assoc request :fleet-export-result result)))
        (catch Exception e
          (assoc (browser/current! deps {:params {"error" (ex-message e)}}) :status 422))))))
