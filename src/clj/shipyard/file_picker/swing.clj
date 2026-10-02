(ns shipyard.file-picker.swing
  "Desktop folder and ZIP selection using the JDK's Swing toolkit."
  (:import [java.awt GraphicsEnvironment]
           [java.util.concurrent CompletableFuture CompletionException]
           [javax.swing JFileChooser SwingUtilities]
           [javax.swing.filechooser FileNameExtensionFilter]))

(defn available? [] (not (GraphicsEnvironment/isHeadless)))

(defn- show-dialog! [kind]
  (let [folder? (= kind "directory")
        chooser (doto (JFileChooser.)
                  (.setDialogTitle (if folder? "Choose library folder" "Choose ZIP archive"))
                  (.setFileSelectionMode (if folder? JFileChooser/DIRECTORIES_ONLY JFileChooser/FILES_ONLY))
                  (.setMultiSelectionEnabled false)
                  (.setAcceptAllFileFilterUsed false))]
    (when-not folder?
      (.setFileFilter chooser (FileNameExtensionFilter. "ZIP archives (*.zip)" (into-array String ["zip"]))))
    (let [result (.showOpenDialog chooser nil)]
      (cond
        (= result JFileChooser/APPROVE_OPTION) (.getAbsolutePath (.getSelectedFile chooser))
        (= result JFileChooser/CANCEL_OPTION) nil
        :else (throw (ex-info "The file selector could not open. Check that Shipyard has access to your graphical desktop." {}))))))

(defn choose! [kind]
  (when-not (available?)
    (throw (ex-info "No graphical desktop is available to Shipyard. Open Shipyard in a graphical desktop session to use the file selector." {})))
  (if (SwingUtilities/isEventDispatchThread)
    (show-dialog! kind)
    (let [result (CompletableFuture.)]
      (SwingUtilities/invokeLater
       (fn []
         (try
           (.complete result (show-dialog! kind))
           (catch Throwable e
             (.completeExceptionally result e)))))
      ;; join keeps the dialog lock held even if the HTTP thread is interrupted.
      ;; All construction, interaction and disposal remain on Swing's UI thread.
      (try
        (.join result)
        (catch CompletionException e
          (throw (.getCause e)))))))
