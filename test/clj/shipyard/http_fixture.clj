(ns shipyard.http-fixture
  "An isolated, socket-free backend system shared by HTTP/settings fixtures."
  (:require [babashka.fs :as fs]
            [shipyard.system :as system]))

(defn start! [root]
  (let [home (fs/create-temp-dir {:prefix "shipyard-http-system-"})
        config-dir (str (fs/path home "config"))
        config (system/read-config! "shipyard/systems/http.edn"
                                    {:shipyard.store/db {:directory (str (fs/path home "database"))}
                                     :shipyard.library/index {:root (some-> root str) :config-dir config-dir}
                                     :shipyard.mesh/cache {:cache-home (str home)}})]
    (try
      (let [started (system/start! config)]
        (with-meta {:workers (:shipyard.jobs/pool started)
                    :library (:shipyard.library/index started)
                    :catalog (:shipyard.catalog/db started)
                    :cache (:shipyard.mesh/cache started)
                    :jobs (:shipyard.http/jobs started)
                    :config-dir config-dir}
          {::system started ::home home}))
      (catch Exception error
        (fs/delete-tree home)
        (throw error)))))

(defn stop! [fixture]
  (system/stop! (::system (meta fixture)))
  (fs/delete-tree (::home (meta fixture))))
