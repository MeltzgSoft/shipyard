(ns shipyard.desktop.backend
  "Own one JVM child. Readiness belongs to its private stdout and launch nonce;
  shutdown uses stdin on every platform, with EOF as the parent-crash fallback."
  (:require [clojure.string :as str]
            [shipyard.desktop.transforms :as t]))

(defn payload
  "Resolve explicit development or packaged payload. Packaged applications
  never fall back to a system JVM or development checkout."
  [{:keys [packaged? app-path resources-path platform java]}]
  (let [path (js/require "node:path")
        root (when-not packaged? (.resolve path app-path ".."))
        resources (if packaged? resources-path root)]
    {:java (if packaged?
             (.join path resources "runtime" "bin" (if (= platform "win32") "java.exe" "java"))
             (or java "java"))
     :jar (if packaged? (.join path resources "shipyard.jar")
              (.join path root "target" "shipyard-0.1.0-SNAPSHOT.jar"))
     :cwd resources}))

(defn spawn-owned!
  "Lower-level owned process boundary, also used by actual child-process tests.
  Returns {child, ready: Promise, stop(): Promise}. No server probe occurs until
  a valid readiness line carrying this launch's nonce has arrived."
  [{:keys [command args cwd env token startup-ms shutdown-ms on-exit]
    :or {startup-ms 240000 shutdown-ms 60000}}]
  (let [cp (js/require "node:child_process")
        child (.spawn cp command (clj->js args)
                      #js {:cwd cwd :env env :stdio #js ["pipe" "pipe" "pipe"] :windowsHide true})
        state (atom {:buffer "" :diagnostics "" :settled? false :dead? false :stopping? false})
        resolve-exit (atom nil)
        exited (js/Promise. (fn [resolve _] (reset! resolve-exit resolve)))
        stop-result (atom nil)
        ready
        (js/Promise.
         (fn [resolve reject]
           (let [timer (atom nil)
                 health-timer (atom nil)
                 controller (js/AbortController.)
                 finish! (fn [error value]
                           (when-not (:settled? @state)
                             (swap! state assoc :settled? true)
                             (js/clearTimeout @timer)
                             (js/clearTimeout @health-timer)
                             (.abort controller)
                             (if error
                               (reject (js/Error. (str error (when (not-empty (:diagnostics @state))
                                                               (str "\n" (:diagnostics @state))))))
                               (resolve (clj->js value)))))
                 probe! (fn probe! [value]
                          (when-not (or (:settled? @state) (:dead? @state))
                            (-> (js/fetch (str (:url value) "/healthz")
                                          #js {:redirect "manual" :signal (.-signal controller)})
                                (.then (fn [response]
                                         (if (= 200 (.-status response))
                                           (finish! nil value)
                                           (reset! health-timer (js/setTimeout #(probe! value) 100)))))
                                (.catch (fn [_]
                                          (when-not (:settled? @state)
                                            (reset! health-timer (js/setTimeout #(probe! value) 100))))))))]
             (reset! timer (js/setTimeout #(finish! "Owned backend startup timed out" nil) startup-ms))
             (.setEncoding (.-stdout child) "utf8")
             (.setEncoding (.-stderr child) "utf8")
             (.on (.-stderr child) "data"
                  (fn [chunk]
                    (swap! state update :diagnostics #(let [s (str % chunk)] (subs s (max 0 (- (count s) 16384)))))))
             (.on (.-stdout child) "data"
                  (fn [chunk]
                    (when-not (:settled? @state)
                      (let [buffer (str (:buffer @state) chunk)
                            lines (str/split buffer #"\n" -1)]
                        (if (or (> (count buffer) 65536) (some #(> (count %) t/max-line-bytes) lines))
                          (finish! "Owned backend output exceeded protocol limit" nil)
                          (do
                            (swap! state assoc :buffer (last lines))
                            (doseq [line (butlast lines)]
                              (when-let [value (t/readiness token (str/replace line #"\r$" ""))]
                                (if (:error value)
                                  (finish! (:error value) nil)
                                  (when-not (:probing? @state)
                                    (swap! state assoc :probing? true)
                                    (probe! value)))))))))))
             (.on child "error"
                  (fn [error]
                    (swap! state assoc :dead? true)
                    (@resolve-exit #js {:error (.-message error)})
                    (finish! (str "Could not launch owned backend: " (.-message error)) nil)))
             (.on (.-stdin child) "error" (fn [_] nil))
             (.on child "exit"
                  (fn [code signal]
                    (swap! state assoc :dead? true)
                    (@resolve-exit #js {:code code :signal signal})
                    (if-not (:settled? @state)
                      (finish! (str "Owned backend exited before readiness (" code ", " signal ")") nil)
                      (when (and on-exit (not (:stopping? @state)))
                        (on-exit code signal))))))))]
    #js {:child child :ready ready
         :stop (fn []
                 (or @stop-result
                     (let [result
                           (js/Promise.
                            (fn [resolve _]
                              (swap! state assoc :stopping? true)
                              (when-not (:dead? @state)
                                (.end (.-stdin child) t/stop-command))
                              (let [timer (js/setTimeout
                                           #(when-not (:dead? @state) (.kill child "SIGKILL"))
                                           shutdown-ms)]
                                (.then exited (fn [value] (js/clearTimeout timer) (resolve value))))))]
                       (reset! stop-result result)
                       result)))}))

(defn start! [options]
  (let [{:keys [java jar cwd]} (payload options)
        fs (js/require "node:fs")
        token (.randomUUID (js/require "node:crypto"))]
    (when-not (.existsSync fs jar)
      (throw (js/Error. (str "Missing Shipyard JAR: " jar ". Build the desktop payload first."))))
    (when (and (:packaged? options) (not (.existsSync fs java)))
      (throw (js/Error. (str "Missing bundled Java runtime: " java))))
    (spawn-owned! (merge options
                         {:command java :cwd cwd :token token
                          :args ["--enable-native-access=ALL-UNNAMED" "--sun-misc-unsafe-memory-access=allow"
                                 "-jar" jar "--desktop"]
                          :env (js/Object.assign #js {} (.-env js/process) (:env options)
                                                 #js {:SHIPYARD_DESKTOP_TOKEN token})}))))

;; Small JS API for actual Node process tests, without importing Electron.
(defn ^:export start-backend [options]
  (start! (assoc (js->clj options :keywordize-keys true) :env (.-env options))))

(defn ^:export spawn-backend [options]
  (let [opts (js->clj options :keywordize-keys true)]
    (spawn-owned! (assoc opts :env (or (.-env options) (.-env js/process))))))
