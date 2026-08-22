(ns shipyard.unit.reflection-test
  "Fails if any source namespace compiles with reflection.

  Replaces per-namespace `(set! *warn-on-reflection* true)`, which only covered
  the namespaces somebody remembered to annotate and put a build concern in
  source. This covers every namespace automatically, including ones not written
  yet, and it fails rather than printing a warning nobody reads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn source-namespaces
  "Every namespace under the JVM source roots, derived from file paths.

  Uses Path/relativize rather than stripping a root prefix with a regex: on
  Windows `file-seq` yields `src\\clj\\...`, which no pattern built from the
  forward-slash root will match, and the symbol silently comes out as
  `src.clj.shipyard.http.routes`."
  []
  (for [root ["src/clj" "src/cljc"]
        :let [root-path (.toPath (io/file root))]
        ^java.io.File f (file-seq (io/file root))
        :when (and (.isFile f) (re-find #"\.cljc?$" (.getName f)))]
    (-> (.relativize root-path (.toPath f))
        str
        (str/replace "\\" "/")
        (str/replace #"\.cljc?$" "")
        (str/replace "_" "-")
        (str/replace "/" ".")
        symbol)))

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
