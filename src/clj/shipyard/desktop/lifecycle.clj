(ns shipyard.desktop.lifecycle
  "An owned desktop JVM uses its parent's private pipes for readiness and stop."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [shipyard.desktop.transforms :as transforms]
            [shipyard.http.server :as server]
            [shipyard.system :as system])
  (:import [java.io BufferedReader Writer]))

(defn- stop-once! [started]
  (let [claimed (atom false)
        completed (promise)]
    (fn []
      (if (compare-and-set! claimed false true)
        (try
          (system/stop! started)
          (deliver completed nil)
          (catch Throwable error
            (deliver completed error)
            (throw error)))
        (when-let [error @completed]
          (throw error))))))

(defn- await-stop! [input]
  (let [^BufferedReader reader (io/reader input)]
    (loop []
      (when-not (transforms/stop-command? (.readLine reader))
        (recur)))))

(defn run!
  "Serve before publishing the actual bound port. Stop on the private command
  or pipe EOF, and finish durable shutdown before returning. Injectable streams
  and configuration let integration tests exercise the actual server and store."
  [{:keys [config input output token]}]
  (when-not (transforms/valid-token? token)
    (throw (ex-info "Desktop startup requires a valid launch token" {})))
  (let [started (system/start! (transforms/desktop-config config))
        stop! (stop-once! started)
        hook (Thread. ^Runnable stop! "shipyard-desktop-shutdown")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [^Writer writer output]
        (.write writer (str (transforms/ready-line token (server/port! (:shipyard.http/server started))) "\n"))
        (.flush writer))
      (await-stop! input)
      (finally
        (try
          (stop!)
          (finally
            (try
              (.removeShutdownHook (Runtime/getRuntime) hook)
              (catch IllegalStateException _
                ;; A concurrent JVM shutdown already owns the hook.
                nil))))))))
