(ns shipyard.supported-spike
  "On-demand, read-only source-pair probe. Writes only the caller's report file."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            [shipyard.mesh.stl :as stl]
            [shipyard.supported-spike.alignment :as alignment]))

(defn probe! [part-directory]
  (let [started (System/nanoTime)
        directory (fs/path part-directory)
        source (stl/parse-file! (fs/file directory "unsupported.stl"))
        target (stl/parse-file! (fs/file directory "supported.stl"))]
    (assoc (alignment/align source target) :part-directory (str directory)
           :elapsed-ms (/ (- (System/nanoTime) started) 1.0e6))))

(defn -main [output & directories]
  (when (or (nil? output) (empty? directories))
    (throw (ex-info "Usage: clojure -M:dev -m shipyard.supported-spike /tmp/report.edn PART-DIRECTORY..." {})))
  (let [report (mapv (fn [directory]
                       (println "Probing" directory)
                       (try (probe! directory)
                            (catch Exception e {:part-directory directory :status :error :message (ex-message e)}))) directories)]
    (with-open [writer (io/writer output)]
      (binding [*out* writer] (pprint/pprint report)))
    (doseq [result report] (pprint/pprint (dissoc result :matrix)))))
