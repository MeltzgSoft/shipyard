(ns user
  "REPL workflow helpers (Integrant). Start Shipyard with (go), reload changed
  namespaces and restart with (reset), and stop it with (halt)."
  (:require [integrant.repl :as ig-repl]
            [shipyard.system :as system]))

(defn config
  "Load Shipyard's system configuration for an Integrant REPL session."
  []
  (system/load-config))

(ig-repl/set-prep! config)

(defn go [] (ig-repl/go))
(defn reset [] (ig-repl/reset))
(defn halt [] (ig-repl/halt))

(comment
  (go)
  (reset)
  (halt))
