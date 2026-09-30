(ns shipyard.importer.handlers
  (:require [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.http.htmx :as htmx]
            [shipyard.importer.db :as db]
            [shipyard.system :as system]
            [shipyard.workspace.db :as workspace]
            [shipyard.workspace.handlers :as workspaces]))

(defn- error-response [e]
  (htmx/fragment [:p#import-status.detail__error {:hx-swap-oob "outerHTML" :role "alert"} (.getMessage ^Exception e)]
                 {:status 422}))

(defn- transition! [deps]
  (workspaces/transition! deps {:path-params {:mode "browse"} :params {} :headers {"hx-request" "true"}}))

(defn start! [{:keys [workspace] :as deps} {:keys [params]}]
  (try
    (when (db/session! deps) (throw (ex-info "Finish or cancel the current import first." {})))
    (let [session (db/prepare! deps (system/expand-home (System/getProperty "user.home") (get params "archive")))
          before (workspace/workspace! workspace :browse)]
      (workspace/update-workspace! workspace :browse
                                   (constantly {:view :table :filters {} :colors (:colors before)
                                                :import (assoc session :before before)}))
      (transition! deps))
    (catch Exception e (error-response e))))

(defn- finish! [{:keys [workspace] :as deps} session]
  (db/close! session)
  (workspace/update-workspace! workspace :browse (constantly (:before session)))
  (transition! deps))

(defn cancel! [deps _]
  (try
    (if-let [session (db/session! deps)] (finish! deps session)
            (throw (ex-info "No import is active." {})))
    (catch Exception e (error-response e))))

(defn commit! [deps _]
  (try
    (if-let [session (db/session! deps)]
      (let [{:keys [files parts]} (db/commit! deps session)
            response (finish! deps session)]
        (update response :body str (:body (htmx/fragment [:p#import-status {:hx-swap-oob "outerHTML" :role "status"}
                                                          (str "Imported " files " files in " parts " parts. Original archive kept.")]))))
      (throw (ex-info "No import is active." {})))
    (catch Exception e (error-response e))))

(defn selection! [deps {:keys [params]}]
  (try
    (if-let [session (db/session! deps)]
      (bulk/select-ids! deps (if (= "all" (get params "selection")) (keys @(:entries session)) []))
      (throw (ex-info "No import is active." {})))
    (catch Exception e (error-response e))))
