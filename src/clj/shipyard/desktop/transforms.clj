(ns shipyard.desktop.transforms
  "Desktop startup and private process protocol decisions."
  (:import [java.net BindException]))

(def ready-prefix "SHIPYARD_DESKTOP_READY")
(def stop-command "SHIPYARD_DESKTOP_STOP")

(defn desktop-mode? [args]
  (boolean (some #{"--desktop"} args)))

(defn desktop-config [config]
  (update config :shipyard.http/server merge
          {:host "127.0.0.1" :port 8080 :last-port 65535}))

(defn valid-token? [token]
  (boolean (and (string? token)
                (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" token))))

(defn ready-line [token port]
  (when-not (and (valid-token? token) (integer? port) (<= 8080 port 65535))
    (throw (ex-info "Invalid desktop readiness token or bound port" {:port port})))
  (str ready-prefix " " token " " port))

(defn stop-command? [line]
  (or (nil? line) (= stop-command line)))

(defn bind-failure? [error]
  (loop [cause error]
    (cond
      (nil? cause) false
      (instance? BindException cause) true
      :else (recur (.getCause ^Throwable cause)))))
