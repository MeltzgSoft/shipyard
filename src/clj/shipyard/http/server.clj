(ns shipyard.http.server
  "Jetty lifecycle. Kept separate from the handler so the handler stays a pure
  function of its dependencies and can be tested without a socket."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [shipyard.desktop.transforms :as desktop])
  (:import [org.eclipse.jetty.server Server ServerConnector]))

(defn port! [^Server server]
  (.getLocalPort ^ServerConnector (first (.getConnectors server))))

(defn stop! [^Server server]
  (when server
    (.stop server)
    ;; The listening socket and all server threads must be gone before retry
    ;; or before the database components are closed by Integrant.
    (.join server)))

(defn- bind! [handler host port]
  (let [created (atom nil)]
    (try
      {:server (jetty/run-jetty handler {:port port :host host :join? false
                                         :configurator #(reset! created %)})}
      (catch Exception error
        (when-let [^Server server @created]
          (try
            (stop! server)
            (.destroy server)
            (catch Exception cleanup-error
              (.addSuppressed error cleanup-error))))
        {:error error}))))

(defn start!
  "Bind the actual server, retrying only a genuine bind failure when the caller
  explicitly supplies a last port. Ordinary server startup has one attempt."
  [{:keys [port last-port host handler]}]
  (loop [candidate port]
    (let [{:keys [server error]} (bind! handler host candidate)]
      (cond
        server (do (log/info (format "shipyard listening on http://%s:%d" host (port! server)))
                   server)
        (and last-port (< candidate last-port) (desktop/bind-failure? error))
        (recur (inc candidate))
        :else (throw error)))))

(defmethod ig/init-key :shipyard.http/server [_ options]
  (start! options))

(defmethod ig/halt-key! :shipyard.http/server [_ server]
  (stop! server))
