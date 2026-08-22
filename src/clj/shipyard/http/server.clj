(ns shipyard.http.server
  "Jetty lifecycle. Kept separate from the handler so the handler stays a pure
  function of its dependencies and can be tested without a socket."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server Server ServerConnector]))

(defmethod ig/init-key :shipyard.http/server [_ {:keys [port host handler]}]
  (let [server (jetty/run-jetty handler {:port port :host host :join? false})]
    (log/info (format "shipyard listening on http://%s:%d" host
                      ;; .getConnectors returns Connector[]; `first` erases to
                      ;; Object, so .getLocalPort needs the concrete type.
                      (.getLocalPort ^ServerConnector
                                     (first (.getConnectors ^Server server)))))
    server))

(defmethod ig/halt-key! :shipyard.http/server [_ ^Server server]
  (when server
    (.stop server)
    ;; Block until the port is actually released, otherwise an immediate
    ;; restart in the same REPL session races and fails to bind.
    (.join server)))
