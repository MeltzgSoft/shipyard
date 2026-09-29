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

(def ^:const move-attempts
  "Five tries with a linear backoff - 50, 100, 150, 200 ms. A scanner's hold on
  a small file is milliseconds; anything still failing after three quarters of a
  second is a real permission problem and should surface as one."
  5)

(defn write-atomically!
  "Write via a temp file and rename, so a concurrent reader never sees a partial
  file.

  `:atomic-move` is not supported on every filesystem, so fall back rather than
  fail: the consequence is a torn read under concurrency, not corruption, and
  refusing to start is worse.

  **And retry first, because Windows.** A file written moments ago can still be
  held open by the search indexer or a virus scanner when the move fires, and
  `Files.move` reports that as `AccessDeniedException` rather than as anything
  that reads like `busy`. Windows CI caught it intermittently on the index write
  that follows a preprocess - which is exactly the class of failure §9 says the
  Windows job is there to find. The hold is short, so a few backed-off retries
  clear it.

  Used for explicit report exports. Application settings and scan metadata use
  Datalevin transactions instead."
  [target ^String content]
  (let [target (fs/absolutize target)]
    (fs/create-dirs (fs/parent target))
    (let [tmp (fs/create-temp-file {:dir (fs/parent target) :prefix "shipyard-" :suffix ".tmp"})]
      (spit (fs/file tmp) content)
      (loop [attempt 1, atomic? true]
        (let [outcome (try
                        (fs/move tmp target (cond-> {:replace-existing true}
                                              atomic? (assoc :atomic-move true)))
                        :done
                        ;; Must precede the FileSystemException catch: it is a
                        ;; subclass, and this one is not worth retrying.
                        (catch java.nio.file.AtomicMoveNotSupportedException _ :fallback)
                        (catch java.nio.file.FileSystemException e
                          (if (< attempt move-attempts)
                            :retry
                            (throw e))))]
          (case outcome
            :done     nil
            :fallback (recur attempt false)
            :retry    (do (Thread/sleep (* 50 (long attempt)))
                          (recur (inc attempt) atomic?))))))))

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
   (ig/init cfg)))

(defn stop! [system] (when system (ig/halt! system)))
