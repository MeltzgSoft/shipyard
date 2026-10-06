(ns shipyard.cli
  "CLI setup: resolve application settings through a short-lived store system."
  (:require [babashka.fs :as fs]
            [shipyard.settings.db :as settings]
            [shipyard.system :as system]))

(defn configured-root!
  "Read the selected root without creating an application database on first use.
  An explicit tool --root should bypass this lookup."
  [config]
  (let [{:keys [directory data-home] :as options} (:shipyard.store/db config)
        directory (or directory (fs/path (or data-home (system/data-home!)) "shipyard" "database"))]
    (if (fs/exists? directory)
      (let [started (system/start! (system/read-config! "systems/store.edn"
                                                        {:shipyard.store/db options}))]
        (try (settings/tool-root! (:shipyard.store/db started) config)
             (finally (system/stop! started))))
      (settings/tool-root! nil config))))
