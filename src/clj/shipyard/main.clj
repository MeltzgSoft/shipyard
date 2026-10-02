(ns shipyard.main
  (:require [clojure.tools.logging :as log]
            [shipyard.desktop.lifecycle :as desktop]
            [shipyard.desktop.transforms :as transforms]
            [shipyard.system :as system])
  (:gen-class))

(defn -main [& args]
  (if (transforms/desktop-mode? args)
    (do
      (desktop/run! {:config (system/load-config!)
                     :input System/in
                     :output *out*
                     :token (or (System/getenv "SHIPYARD_DESKTOP_TOKEN") (str (random-uuid)))})
      ;; Desktop shutdown already flushed and closed the system. Exit any JVM
      ;; support threads as well; the private pipe, not an HTTP route, owns quit.
      (System/exit 0))
    (let [started (system/start!)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable
                         (fn []
                           (log/info "shutting down")
                           (system/stop! started))))
      @(promise))))
