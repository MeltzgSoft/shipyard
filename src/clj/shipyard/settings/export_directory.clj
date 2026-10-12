(ns shipyard.settings.export-directory
  "Inspect and create export destinations independently of library relocation."
  (:require [shipyard.http.settings :as settings])
  (:import [java.nio.file Files Path Paths LinkOption]
           [java.nio.file.attribute FileAttribute]))

(defn resolved-path!
  "Resolve existing ancestors, including symlinks, before appending missing children."
  [^Path path]
  (let [path (.normalize (.toAbsolutePath path))]
    (if (Files/exists path (make-array LinkOption 0))
      (.toRealPath path (make-array LinkOption 0))
      (.resolve ^Path (resolved-path! (.getParent path)) (.getFileName path)))))

(defn prepare!
  "Validate separation and writability, creating a missing export directory.
  Returns a canonical path; never writes or removes a completed bundle."
  [raw library-root]
  (let [normal (settings/normalise (System/getProperty "user.home") raw)]
    (when-not normal (throw (ex-info "Enter an export directory." {})))
    (let [path (resolved-path! (Paths/get normal (make-array String 0)))
          library (when library-root (resolved-path! (Paths/get library-root (make-array String 0))))]
      (when (and library (.startsWith ^Path path ^Path library))
        (throw (ex-info "Choose an export directory outside the library tree." {})))
      (Files/createDirectories path (make-array FileAttribute 0))
      (when-not (Files/isWritable path)
        (throw (ex-info "Shipyard cannot write to the export directory." {})))
      (let [probe (Files/createTempFile path ".shipyard-write-" ".tmp" (make-array FileAttribute 0))]
        (Files/delete probe))
      (str path))))
