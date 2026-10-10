(ns shipyard.preparation.routes
  (:require [shipyard.preparation :as preparation]
            [shipyard.preparation.transforms :as transforms]))

(defn status! [{:keys [preparation]} {:keys [path-params]}]
  (if-let [entry (preparation/status! preparation (:resource path-params))]
    {:status 200 :headers {"content-type" "application/edn; charset=utf-8" "cache-control" "no-store"}
     :body (pr-str (transforms/envelope entry))}
    {:status 410 :body "Preparation expired or its source changed."}))

(defn data! [{:keys [preparation]} {:keys [path-params]}]
  (if-let [entry (preparation/status! preparation (:resource path-params))]
    (if (= :ready (:state entry))
      {:status 200 :headers {"content-type" (:content-type entry) "cache-control" "no-store"}
       :body (:bytes entry)}
      {:status 409 :body "Preparation is not ready."})
    {:status 410 :body "Preparation expired or its source changed."}))

(defn routes [deps]
  (when (:preparation deps)
    [["/preparation/:resource" {:get {:handler (partial status! deps)}}]
     ["/preparation/:resource/data" {:get {:handler (partial data! deps)}}]]))
