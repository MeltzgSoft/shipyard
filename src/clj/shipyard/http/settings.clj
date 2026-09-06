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

(defn normalise!
  "The path as it will be used: trimmed, `~` expanded, or nil if there is
  nothing there."
  [path]
  (some-> path (str) (str/trim) (not-empty) (system/expand-home!)))

(defn problem!
  "Why `path` cannot be a library root, in the user's terms, or nil when it
  can.

  It checks the directory rather than its contents on purpose. An empty folder,
  or one holding models Shipyard cannot parse, is a real answer to \"where is
  your library\" - the library panel reports finding nothing, which is true and
  fixable. Refusing the path would instead be Shipyard telling the user they
  are wrong about where their own files are."
  [path]
  (let [p (normalise! path)
        f (some-> p fs/file)]
    (cond
      (nil? p)                  "Enter the folder that holds your STL library."
      (not (fs/exists? f))      (str "No such folder: " p)
      (not (fs/directory? f))   (str "Not a folder: " p)
      (not (fs/readable? f))    (str "Shipyard cannot read " p)
      :else                     nil)))

(defn relocate!
  "Point the application at `path`: persist it, rescan, re-ingest, and drop the
  job table. Returns nil on success, or the reason it refused.

  **Persisted first**, for the reason §4 already gives for the catalog: if the
  write throws, nothing has changed and the message is accurate. Applying first
  would leave a running application whose library silently reverts at the next
  restart, which is the failure nobody thinks to check for."
  [{:keys [library catalog jobs config-dir]} path]
  (or (problem! path)
      (let [root (normalise! path)]
        (if-let [failure (try
                           (if config-dir
                             (system/save-library-root! config-dir root)
                             (system/save-library-root! root))
                           nil
                           (catch Exception e
                             (log/warn e "could not save the library root")
                             (str "Could not save the setting: " (ex-message e))))]
          failure
          (let [{:keys [parts root]} (index/set-root! library root)]
            (db/reingest! catalog parts root)
            (jobs/clear! jobs)
            (log/info "library relocated to" root)
            nil)))))
