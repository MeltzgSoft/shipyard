(ns shipyard.catalog.layers
  "Durable shared layer registry for one library."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [shipyard.regions.registry :as registry]
            [shipyard.system :as system]))

(defn file ^java.io.File [root] (io/file root "shipyard-layers.edn"))

(defn read! [root]
  (if (and root (.exists (file root)))
    (let [value (edn/read-string (slurp (file root)))]
      (when-not (registry/valid? value)
        (throw (ex-info "Invalid shared layer registry. Restore shipyard-layers.edn before reopening the library." {})))
      value)
    registry/empty-registry))

(defn write! [root value]
  (when-not (and root (registry/valid? value)) (throw (ex-info "Invalid shared layer registry" {})))
  (when (and (.exists (file root)) (not (.isFile (file root))))
    (throw (ex-info "Shared layer registry must be a regular file" {})))
  (system/write-atomically! (file root) (pr-str value)))
