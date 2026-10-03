(ns shipyard.settings.db
  "Application settings in the shared store; legacy files are read only when
  initializing a database that has no saved selection."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.store.db :as store]
            [shipyard.settings.transforms :as transforms]
            [shipyard.system :as system]))

(defn library-root! [database]
  (store/read! database
               #(:settings/library-root (d/pull % [:settings/library-root]
                                                [:store/key "shipyard"]))))

(defn save-library-root! [database root]
  (when-not (and (string? root) (not (str/blank? root)))
    (throw (ex-info "Library root must be a nonblank path" {:root root})))
  (store/write! database
                #(d/transact! % [{:store/key "shipyard" :settings/library-root root}]))
  root)

(defn cut-defaults! [database]
  (or (store/read! database
                   #(:settings/mount-cut-defaults (d/pull % [:settings/mount-cut-defaults]
                                                          [:store/key "shipyard"])))
      transforms/cut-defaults))

(defn save-cut-defaults! [database values]
  (store/write! database
                #(d/transact! % [{:store/key "shipyard" :settings/mount-cut-defaults values}]))
  values)

(defn- legacy-root! [config-dir]
  (when config-dir
    (let [file (fs/file config-dir "shipyard" "library.edn")]
      (when (fs/regular-file? file)
        (try
          (let [root (:root (edn/read-string (slurp file)))]
            (when (and (string? root) (not (str/blank? root))) root))
          (catch Exception e
            (log/warn "ignoring unreadable legacy library setting:" (ex-message e))
            nil))))))

(defn initial-root!
  "Resolve the library after opening the store. A saved DB selection wins over
  the old settings file and any bootstrap root. Neither file is rewritten."
  [{:keys [lock] :as database} configured-root config-dir]
  (locking lock
    (or (library-root! database)
        (when-let [root (or (legacy-root! config-dir) (not-empty configured-root))]
          (save-library-root! database (system/expand-home (System/getProperty "user.home") root))))))

(defn configured-root!
  "Read the selected root for command-line tools without changing the setting.
  An explicit tool --root should bypass this lookup."
  [config]
  (let [{:keys [directory data-home] :as options} (:shipyard.store/db config)
        directory (or directory (fs/path (or data-home (system/data-home!)) "shipyard" "database"))
        root (when (fs/exists? directory)
               (let [database (ig/init-key :shipyard.store/db options)]
                 (try (library-root! database)
                      (finally (store/close! database)))))]
    (or root
        (some-> (or (legacy-root! (get-in config [:shipyard.library/index :config-dir]))
                    (get-in config [:shipyard.library/index :root]))
                (#(system/expand-home (System/getProperty "user.home") %))))))
