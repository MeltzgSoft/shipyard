(ns shipyard.store.legacy
  "Read-only legacy imports. Originals are never changed or removed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [shipyard.loadout.transforms :as loadout]
            [shipyard.scheme.transforms :as scheme]
            [shipyard.regions.registry :as registry]
            [shipyard.regions.model :as regions])
  (:import [java.io PushbackReader]))

(defn read! [file valid?]
  (when (.exists (io/file file))
    (try
      (with-open [reader (PushbackReader. (io/reader file))]
        (let [value (edn/read {:eof ::eof} reader)]
          (when-not (and (= ::eof (edn/read {:eof ::eof} reader)) (valid? value))
            (throw (ex-info "Invalid or unsupported legacy data" {})))
          value))
      (catch Exception e
        (throw (ex-info (str "Cannot import " file ". Repair or restore the original; no import was committed.")
                        {:code :invalid-store :file (str file)} e))))))

(defn part! [root id]
  (read! (io/file root id "shipyard.edn")
         #(and (map? %) (or (nil? (:shipyard/version %)) (= 1 (:shipyard/version %)))
               (or (nil? (:mounts %)) (sequential? (:mounts %)))
               (or (nil? (:part/paint-regions %)) (regions/valid? (:part/paint-regions %))))))

(defn layers! [root]
  (or (read! (io/file root "shipyard-layers.edn") registry/valid?) registry/empty-registry))

(defn records! [directory files]
  {:loadouts (:loadouts (read! (or (:loadouts files) (io/file (str directory) "loadouts.edn")) loadout/store?))
   :schemes (:schemes (read! (or (:schemes files) (io/file (str directory) "schemes.edn")) scheme/store?))})
