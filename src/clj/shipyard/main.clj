(ns shipyard.main
  (:require [clojure.tools.logging :as log]
            [shipyard.system :as system])
  (:gen-class))

(defonce ^:private running (atom nil))

(defn -main [& _]
  (let [sys (system/start!)]
    (reset! running sys)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                       (fn []
                         (log/info "shutting down")
                         (system/stop! sys))))
    @(promise)))
