(ns shipyard.report
  "Persistence for EDN reports produced by command-line workflows."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

(defn write-report!
  "Write `report` as EDN and return `file`."
  [file report]
  (let [target (fs/file file)]
    (io/make-parents target)
    (spit target (with-out-str (pp/pprint report))))
  file)
