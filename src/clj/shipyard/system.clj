(ns shipyard.system
  "Configuration loading and integrant lifecycle.

  Configuration is data (resources/config.edn); this namespace only resolves and
  starts it. Application settings are resolved from the shared store after
  bootstrap configuration has located and opened that store."
  (:require [aero.core :as aero]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; aero needs to know how to read integrant refs out of config.edn
(defmethod aero/reader 'ig/ref [_ _ value] (ig/ref value))

(defn- xdg!
  "XDG base directory with the spec's documented fallback."
  [env-var fallback]
  (or (not-empty (System/getenv env-var))
      (str (System/getProperty "user.home") "/" fallback)))

(defn config-home! [] (xdg! "XDG_CONFIG_HOME" ".config"))
(defn cache-home!  [] (xdg! "XDG_CACHE_HOME"  ".cache"))
(defn data-home!   [] (xdg! "XDG_DATA_HOME"   ".local/share"))

(defn expand-home
  "Expand a leading ~ using an explicit home directory."
  [home path]
  (if (and (string? path) (str/starts-with? path "~"))
    (str home (subs path 1))
    path))

(defn deep-merge [a b]
  (cond (and (map? a) (map? b)) (merge-with deep-merge a b)
        (some? b)               b
        :else                   a))

(defn- user-config!
  "Layer 2. Absent is the normal case, not an error."
  [config-dir]
  (let [f (fs/file config-dir "shipyard" "config.edn")]
    (when (fs/regular-file? f)
      (log/info "loading user config" (str f))
      (aero/read-config f))))

(defn- env-overrides
  "Layer 3. Applied explicitly rather than through `#or` inside config.edn,
  which would be silently discarded whenever a user config replaced the whole
  key.

  **The library root is deliberately not here.** It is set from the settings
  form (issue #35), and an environment variable outranking the form would make
  the form lie about what the application is using."
  [cfg env]
  (cond-> cfg
    (get env "PORT")
    (assoc-in [:shipyard.http/server :port] (parse-long (get env "PORT")))))

(defn load-config!
  "Resolve bootstrap configuration from defaults, user config and environment.

  `config-dir` and `env` are injectable so the layering itself is testable
  without mutating the process environment."
  ([] (load-config! {}))
  ([{:keys [profile config-dir env]
     :or   {profile :default}}]
   (let [env        (or env (into {} (System/getenv)))
         config-dir (or config-dir (config-home!))]
     (-> (aero/read-config (io/resource "config.edn") {:profile profile})
         (deep-merge (user-config! config-dir))
         (env-overrides env)
         (assoc-in [:shipyard.library/index :config-dir] config-dir)
         (update-in [:shipyard.library/index :root]
                    #(expand-home (System/getProperty "user.home") %))))))

(defn start!
  ([] (start! (load-config!)))
  ([cfg]
   (ig/load-namespaces cfg)
   (try
     (ig/init cfg)
     (catch Exception error
       ;; A failed server bind still follows opened database/worker components.
       ;; Integrant includes that partial system in its build exception.
       (when-let [started (:system (ex-data error))]
         (try (ig/halt! started)
              (catch Exception cleanup-error
                (.addSuppressed error cleanup-error))))
       (throw error)))))

(defn stop! [system] (when system (ig/halt! system)))
