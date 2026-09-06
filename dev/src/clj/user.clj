(ns user
  "REPL workflow helpers (Integrant). Start Shipyard with (go), reload changed
  namespaces and restart with (reset), and stop it with (halt)."
  (:require [integrant.core :as ig]
            [integrant.repl :as ig-repl]
            [shipyard.system :as system]))

(defn config
  "Load the config and its Integrant component namespaces for go/reset."
  []
  (doto (system/load-config!) ig/load-namespaces))

(ig-repl/set-prep! config)

(defn go [] (ig-repl/go))
(defn reset [] (ig-repl/reset))
(defn halt [] (ig-repl/halt))

(comment
  (go)
  (reset)
  (halt))
