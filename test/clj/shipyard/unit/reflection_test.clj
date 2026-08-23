(ns shipyard.unit.reflection-test
  "Fails if any source namespace compiles with reflection.

  Replaces per-namespace `(set! *warn-on-reflection* true)`, which only covered
  the namespaces somebody remembered to annotate and put a build concern in
  source. This covers every namespace automatically, including ones not written
  yet, and it fails rather than printing a warning nobody reads."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn source-namespaces
  "Every namespace under the JVM source roots, derived from file paths.

  `fs/components` rather than string surgery on separators. The first version
  stripped the root with a regex built from a forward-slash path, which matched
  nothing on Windows and silently produced `src.clj.shipyard.http.routes` -
  surfacing several frames away as a missing file."
  []
  (for [root ["src/clj" "src/cljc"]
        f    (fs/glob root "**.{clj,cljc}")]
    (let [segs (mapv str (fs/components (fs/relativize root f)))
          segs (update segs (dec (count segs)) #(str/replace % #"\.cljc?$" ""))]
      (symbol (str/replace (str/join "." segs) "_" "-")))))

(deftest no-reflection-in-source
  (let [nss (source-namespaces)
        sw  (java.io.StringWriter.)]
    (is (seq nss) "the source roots should not be empty")
    ;; Fails loudly on a bad derivation instead of letting `require` throw
    ;; FileNotFoundException several frames away.
    (is (every? #(str/starts-with? (str %) "shipyard.") nss)
        (str "namespace names derived from paths look wrong: " (pr-str (vec nss))))
    ;; :reload forces recompilation; without it an already-loaded namespace
    ;; emits nothing and the test passes vacuously.
    (binding [*warn-on-reflection* true
              *err* sw]
      (doseq [n nss] (require n :reload)))
    (let [warnings (->> (str/split-lines (str sw))
                        (filter #(str/includes? % "Reflection warning"))
                        vec)]
      (is (empty? warnings)
          (str "Reflection in the mesh pipeline costs real time on multi-million"
               " triangle meshes. Add a type hint.\n  "
               (str/join "\n  " warnings))))))
