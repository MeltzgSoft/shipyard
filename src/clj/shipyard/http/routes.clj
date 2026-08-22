(ns shipyard.http.routes
  "Ring handler. Scaffold: liveness plus the static shell. Real routes are
  issue #15."
  (:require [integrant.core :as ig]
            [reitit.ring :as ring]))

(defn- healthz [_]
  {:status  200
   :headers {"content-type" "application/json"}
   :body    "{\"status\":\"ok\"}"})

(defn handler [{:keys [library cache]}]
  (ring/ring-handler
   (ring/router
    [["/healthz" {:get healthz}]])
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))))

(defmethod ig/init-key :shipyard.http/routes [_ opts]
  (handler opts))
