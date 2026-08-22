(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'meltzgsoft/shipyard)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s.jar" (name lib) version))

;; All four native classifiers ship in one artifact; LWJGL selects at runtime.
;; The natives are prebuilt jars, so any platform can assemble a jar for every
;; platform - see TECHNICAL.md §9.
(def natives [:natives-linux :natives-windows :natives-macos :natives-macos-arm64])

(defn- sh [& args]
  (let [{:keys [exit]} (b/process {:command-args (vec args)})]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " (pr-str args)) {:exit exit})))))

(defn clean [_] (b/delete {:path "target"}))

(defn frontend
  "npm dependencies, the CLJS release bundle, and htmx.

  htmx is copied rather than bundled so a broken viewport build cannot take the
  whole UI down with it - TECHNICAL.md §8."
  [_]
  (sh "npm" "ci")
  (sh "npx" "shadow-cljs" "release" "viewport")
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
