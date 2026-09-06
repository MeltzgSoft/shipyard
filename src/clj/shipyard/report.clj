(ns shipyard.report
  "Persistence for EDN reports produced by command-line workflows."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [shipyard.system :as system]))

(defn write-report!
  "Write `report` as EDN and return `file`."
  [file report]
  (system/write-atomically! (fs/file file) (with-out-str (pp/pprint report)))
  file)
