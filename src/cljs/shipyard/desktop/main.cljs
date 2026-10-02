(ns shipyard.desktop.main
  "Electron owns the backend and the sole sandboxed application window."
  (:require [shipyard.desktop.backend :as backend]
            [shipyard.desktop.transforms :as t]))

(defn- open-external! [^js shell url]
  (when (t/safe-external? url)
    (.catch (.openExternal shell url) #(js/console.error "Could not open external link" %))))

(defn- protect-window! [^js window origin ^js shell]
  (let [^js contents (.-webContents window)
        ^js session (.-session contents)]
    (.setPermissionRequestHandler session (fn [_ _ callback] (callback false)))
    (.setPermissionCheckHandler session (fn [& _] false))
    (.on contents "will-attach-webview" #(.preventDefault %))
    (.on contents "will-frame-navigate"
         (fn [^js event]
           (when-not (t/same-origin? origin (.-url event))
             (.preventDefault event)
             (when (.-isMainFrame event) (open-external! shell (.-url event))))))
    (.on contents "will-redirect"
         (fn [^js event]
           (when-not (t/same-origin? origin (.-url event)) (.preventDefault event))))
    (.setWindowOpenHandler contents
                           (fn [details]
                             (let [url (.-url details)]
                               (if (t/same-origin? origin url)
                                 (.catch (.loadURL window url) #(js/console.error %))
                                 (open-external! shell url)))
                             #js {:action "deny"}))))

(defn main! []
  (let [electron (js/require "electron")
        app (.-app electron)
        BrowserWindow (.-BrowserWindow electron)
        state (atom {:runtime nil :window nil :quitting? false :stopped? false})
        fail! (fn [error]
                (when-not (:quitting? @state)
                  ;; Error dialogs can block. Release the owned JVM and its store
                  ;; first, including failures during startup or initial page load.
                  (swap! state assoc :quitting? true :stop-pending? true)
                  (-> (js/Promise.resolve (when-let [runtime (:runtime @state)] ((.-stop runtime))))
                      (.then (fn [_]
                               (swap! state assoc :stopped? true)
                               (.showErrorBox (.-dialog electron) "Shipyard could not start"
                                              (or (.-message error) (str error)))
                               (.quit app))))))]
    ;; Respect the existing XDG-backed store and isolate Electron's profile/lock
    ;; beside it. Tests and users with separate data homes cannot focus each other.
    (let [path (js/require "node:path")
          fs (js/require "node:fs")
          home (or (aget (.-env js/process) "XDG_DATA_HOME")
                   (.join path (.homedir (js/require "node:os")) ".local" "share"))
          profile (.join path home "shipyard" "desktop")]
      (.mkdirSync fs profile #js {:recursive true})
      (.setPath app "userData" profile))
    (if-not (.requestSingleInstanceLock app)
      (.quit app)
      (do
        (.on app "second-instance"
             (fn [& _]
               (when-let [^js window (:window @state)]
                 (when (.isMinimized window) (.restore window))
                 (.show window)
                 (.focus window))))
        (.on app "window-all-closed" #(.quit app))
        (.on app "before-quit"
             (fn [^js event]
               (when-not (:stopped? @state)
                 (swap! state assoc :quitting? true)
                 (if-let [runtime (:runtime @state)]
                   (do
                     (.preventDefault event)
                     (when-not (:stop-pending? @state)
                       (swap! state assoc :stop-pending? true)
                       (-> ((.-stop runtime))
                           (.then (fn [_] (swap! state assoc :stopped? true) (.quit app))))))
                   (swap! state assoc :stopped? true)))))
        (-> (.whenReady app)
            (.then
             (fn []
               (when-not (:quitting? @state)
                 (let [runtime (backend/start!
                                {:packaged? (.-isPackaged app)
                                 :app-path (.getAppPath app)
                                 :resources-path (.-resourcesPath js/process)
                                 :platform (.-platform js/process)
                                 :java (aget (.-env js/process) "SHIPYARD_JAVA")
                                 :on-exit (fn [code signal]
                                            (fail! (js/Error. (str "Shipyard backend exited (" code ", " signal ")"))))})]
                   (swap! state assoc :runtime runtime)
                   (-> (.-ready runtime)
                       (.then
                        (fn [ready]
                          (when-not (:quitting? @state)
                            (let [window (BrowserWindow.
                                          #js {:width 1280 :height 900 :minWidth 900 :minHeight 600
                                               :title "Shipyard" :backgroundColor "#141820"
                                               :webPreferences #js {:nodeIntegration false :contextIsolation true
                                                                    :sandbox true :spellcheck false}})]
                              (swap! state assoc :window window)
                              (.on window "closed" #(swap! state assoc :window nil))
                              (protect-window! window (.-url ready) (.-shell electron))
                              (.catch (.loadURL window (.-url ready)) fail!)))))
                       (.catch fail!))))))
            (.catch fail!))))))
