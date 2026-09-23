(ns shipyard.http.settings
  "The library location, as a setting rather than as a deployment detail
  (issue #35).

  Shipyard has exactly one thing it cannot infer: where your STL library is.
  Everything else in `resources/config.edn` is a tunable with a defensible
  default, and this is not - a default here is one developer's home directory.
  So it has no default, it is set from the UI, and this namespace is what
  applying it means.

  Relocating touches three components, and none of them is rebuilt. The route
  table closes over its dependencies at build time
  (`shipyard.http.routes/routes`), so a rebuilt component would be invisible to
  every handler already holding the old one; each of the three mutates in place
  instead."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [shipyard.catalog.db :as db]
            [shipyard.http.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.system :as system]))

(defn normalise
  "Trim a path, expand `~` from an explicit home, or return nil."
  [home path]
  (when-let [trimmed (some-> path (str) (str/trim) (not-empty))]
    (system/expand-home home trimmed)))

(defn path-problem
  "Turn inspected path facts into a user-facing validation result."
  [path {:keys [exists? directory? readable?]}]
  (cond
    (nil? path)       "Enter the folder that holds your STL library."
    (not exists?)     (str "No such folder: " path)
    (not directory?)  (str "Not a folder: " path)
    (not readable?)   (str "Shipyard cannot read " path)
    :else             nil))

(defn- inspect-path! [path]
  (when path
    (let [f (fs/file path)]
      {:exists? (fs/exists? f)
       :directory? (fs/directory? f)
       :readable? (fs/readable? f)})))

(defn problem!
  "Why `path` cannot be a library root, in the user's terms, or nil when it
  can.

  It checks the directory rather than its contents on purpose. An empty folder,
  or one holding models Shipyard cannot parse, is a real answer to \"where is
  your library\" - the library panel reports finding nothing, which is true and
  fixable. Refusing the path would instead be Shipyard telling the user they
  are wrong about where their own files are."
  [path]
  (let [p (normalise (System/getProperty "user.home") path)]
    (path-problem p (inspect-path! p))))

(defn relocate!
  "Validate and import a candidate library before persisting and activating it.
  Failed import or settings writes leave the running library unchanged."
  [{:keys [library catalog jobs config-dir]} path]
  (or (problem! path)
      (let [root (normalise (System/getProperty "user.home") path)]
        (try
          (let [candidate (index/prepare-root! library root)
                staged (db/open! (:store catalog) (:parts candidate) root)]
            (if config-dir
              (system/save-library-root! config-dir root)
              (system/save-library-root! root))
            (reset! (:state library) candidate)
            (reset! (:state catalog) @(:state staged))
            (jobs/clear! jobs)
            (log/info "library relocated to" root)
            nil)
          (catch Exception e
            (log/warn e "could not activate the library")
            (str "Could not save the setting: " (ex-message e)))))))
