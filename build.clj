(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'meltzgsoft/shipyard)
(def version (or (not-empty (System/getenv "SHIPYARD_VERSION")) "0.1.0-SNAPSHOT"))
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s.jar" (name lib) version))

;; All four native classifiers ship in one artifact; LWJGL selects at runtime.
;; The natives are prebuilt jars, so any platform can assemble a jar for every
;; platform - see TECHNICAL.md §9.
(def natives [:natives-linux :natives-windows :natives-macos :natives-macos-arm64])

(defn- windows? []
  (str/includes? (str/lower-case (System/getProperty "os.name")) "windows"))

(defn- npm-command [command]
  (if (windows?) (str command ".cmd") command))

(defn- sh [& args]
  (let [args (cond-> (vec args)
               (#{"npm" "npx"} (first args)) (update 0 npm-command))
        {:keys [exit]} (b/process {:command-args args})]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " (pr-str args)) {:exit exit})))))

(defn clean [_] (b/delete {:path "target"}))

(defn- assert-no-test-hooks
  "The E2E introspection hook must not ship (issue #16, TECHNICAL.md §10.3).

  Checked here rather than trusted, because the way it survives is silent:
  `(and TEST-HOOKS sys)` compiles to `cljs.core/truth_(false) ? … : null`,
  which Closure cannot fold, and the bundle still defines window.__shipyard."
  [bundle]
  (when (re-find #"__shipyard" (slurp bundle))
    (throw (ex-info (str "the release bundle still defines window.__shipyard: "
                         "the goog-define did not constant-fold")
                    {:bundle bundle}))))

(defn frontend
  "npm dependencies, the CLJS release bundle, and htmx.

  htmx is copied rather than bundled so a broken viewport build cannot take the
  whole UI down with it - TECHNICAL.md §8."
  [_]
  (sh "npm" "ci")
  (sh "npx" "shadow-cljs" "release" "viewport")
  (assert-no-test-hooks "resources/public/js/viewport.js")
  (b/copy-file {:src    "node_modules/htmx.org/dist/htmx.min.js"
                :target "resources/public/js/htmx.min.js"}))

(defn uber [_]
  (clean nil)
  (frontend nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases natives})]
    (b/copy-dir {:src-dirs ["src/clj" "src/cljc" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :src-dirs ["src/clj" "src/cljc"] :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      'shipyard.main
             ;; Declared here so the shipped jar needs no --enable-native-access
             ;; flag on the command line (Java 25, issue #6).
             :manifest  {"Enable-Native-Access" "ALL-UNNAMED"}}))
  (println "built" uber-file))

(def desktop-root "target/desktop")

(defn- desktop-version []
  (let [v (str/lower-case version)]
    (when-not (re-matches #"\d+\.\d+\.\d+(?:-[0-9a-z.-]+)?" v)
      (throw (ex-info "SHIPYARD_VERSION must be a semantic release version" {:version version})))
    v))

(defn- desktop-target []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (cond
      (and (str/includes? os "linux") (#{"amd64" "x86_64"} arch)) ["--linux" "--x64"]
      (and (str/includes? os "windows") (#{"amd64" "x86_64"} arch)) ["--win" "--x64"]
      (and (str/includes? os "mac") (#{"aarch64" "arm64"} arch)) ["--mac" "--arm64"]
      :else (throw (ex-info "Desktop packages must be built on a supported native runner"
                            {:os os :arch arch})))))

(defn- stage-desktop! []
  (let [app (str desktop-root "/app")
        resources (str desktop-root "/resources")
        java-home (System/getProperty "java.home")
        jlink (str (io/file java-home "bin" (if (windows?) "jlink.exe" "jlink")))
        runtime (str resources "/runtime")]
    (b/delete {:path desktop-root})
    (b/copy-file {:src "electron/package.json" :target (str app "/package.json")})
    (b/copy-file {:src "electron/compiled/main.js" :target (str app "/compiled/main.js")})
    ;; Change only staged metadata; packaging must not rewrite tracked npm files.
    (sh "npm" "pkg" "set" "--prefix" app (str "version=" (desktop-version)))
    (b/copy-file {:src uber-file :target (str resources "/shipyard.jar")})
    ;; Reflection and native loaders make jdeps under-report runtime use. Keep
    ;; Java SE plus the modules needed by Clojure, JNI, TLS and resource loading.
    (sh jlink "--add-modules" "java.se,jdk.management,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata"
        "--strip-debug" "--no-header-files" "--no-man-pages" "--compress" "zip-6" "--output" runtime)
    (sh (str (io/file runtime "bin" (if (windows?) "java.exe" "java"))) "--version")
    (println "staged desktop payload in" desktop-root)))

(defn- validate-desktop-host! []
  (let [target (desktop-target)]
    (desktop-version)
    (when-not (= 25 (.feature (Runtime/version)))
      (throw (ex-info "Desktop packaging requires the canonical JDK 25" {})))
    target))

(defn package-desktop
  "Package the prebuilt versioned uberjar and released Electron main bundle.

  This task performs no AOT or frontend compilation. Native CI jobs download the
  same payload built once, install shell tools with `npm ci --prefix electron`,
  then link their Java 25 runtime and run electron-builder. `:dir true` emits an
  unpacked app instead of installers. Inputs are preserved byte for byte."
  [{:keys [dir]}]
  (let [target (validate-desktop-host!)]
    (doseq [file [uber-file "electron/compiled/main.js"]]
      (when-not (.isFile (io/file file))
        (throw (ex-info (str "Missing prebuilt desktop payload: " file
                             " — build or download the payload before packaging")
                        {:file file :version version}))))
    (println "Packaging prebuilt desktop payload" uber-file "for" (str/join " " target))
    (stage-desktop!)
    (let [command (into [(npm-command "npm") "exec" "--" "electron-builder" "--publish" "never"]
                        (concat target (when dir ["--dir"])))
          {:keys [exit]} (b/process {:dir "electron" :command-args command})]
      (when-not (zero? exit)
        (throw (ex-info "Electron packaging failed" {:exit exit}))))))

(defn desktop
  "Build and package the native Electron release with its Java 25 runtime.

  Install shell tools with `npm ci --prefix electron` first. This full local task
  compiles the uberjar and released Electron main before calling package-desktop.
  `:dir true` emits an unpacked app; installers are written to electron/dist."
  [opts]
  (validate-desktop-host!)
  (uber opts)
  (sh "npx" "shadow-cljs" "release" "desktop")
  (package-desktop opts))
