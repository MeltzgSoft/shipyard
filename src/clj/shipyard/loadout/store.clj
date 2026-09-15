(ns shipyard.loadout.store
  "Durable, EDN-backed named loadouts.

  This is deliberately a small boundary around the user-data file.  Assembly
  compatibility belongs to the operation layer; this namespace protects the
  durable representation from malformed values and torn writes."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [integrant.core :as ig]
            [shipyard.system :as system])
  (:import [java.util UUID]))

(def ^:const format-version 1)

(defn loadout-file
  "The user-owned store, separate from the library and disposable mesh cache."
  [data-home]
  (fs/file data-home "shipyard" "loadouts.edn"))

(defn- slot-path? [path]
  (and (vector? path) (<= 1 (count path) 16)
       (every? #(and (vector? %) (= 2 (count %))
                     (keyword? (first %)) (integer? (second %))
                     (<= 0 (second %) 255)) path)))

(defn valid-loadout?
  "The durable shape from SPEC §8.3.  Catalog facts are intentionally not
  checked here: the catalog is unavailable at startup and can subsequently
  change; the operations boundary revalidates before a draft changes."
  [{:loadout/keys [id name hull slots scheme thumb] :as loadout}]
  (and (map? loadout)
       (= #{:loadout/id :loadout/name :loadout/hull :loadout/slots
            :loadout/scheme :loadout/thumb}
          (set (keys loadout)))
       (instance? UUID id)
       (string? name) (not-empty name) (<= (count name) 160)
       (string? hull) (not-empty hull)
       (map? slots)
       (every? (fn [[path part-id]] (and (slot-path? path)
                                         (string? part-id) (not-empty part-id))) slots)
       (or (nil? scheme) (instance? UUID scheme))
       (or (nil? thumb) (string? thumb))))

(defn valid-store? [value]
  (and (map? value)
       (= #{:shipyard/version :loadouts} (set (keys value)))
       (= format-version (:shipyard/version value))
       (vector? (:loadouts value))
       (every? valid-loadout? (:loadouts value))
       (= (count (:loadouts value)) (count (set (map :loadout/id (:loadouts value)))))))

(defn- invalid-store! [file value]
  (throw (ex-info (str "Malformed loadout store: " file)
                  {:file (str file) :value value})))

(defn read-store!
  "Read and validate the whole store.  A corrupt file is never treated as an
  empty one, because that would make saved ships silently disappear."
  [file]
  (if-not (fs/exists? file)
    []
    (let [value (try (edn/read-string (slurp (fs/file file)))
                     (catch Exception e
                       (throw (ex-info (str "Malformed loadout store: " file)
                                       {:file (str file)} e))))]
      (when-not (valid-store? value) (invalid-store! file value))
      (:loadouts value))))

(defn loadouts
  "A stable snapshot of saved loadouts, in durable order."
  [{:keys [state]}]
  @state)

(defn replace!
  "Atomically replace all loadouts after validating the complete collection.
  The file is written before the in-memory value changes."
  [{:keys [file state]} value]
  (let [value (vec value)
        store {:shipyard/version format-version :loadouts value}]
    (when-not (valid-store? store) (invalid-store! file store))
    (locking state
      (system/write-atomically! file (pr-str store))
      (reset! state value))))

(defmethod ig/init-key :shipyard.loadout/store [_ {:keys [data-home]}]
  (let [file (loadout-file (or data-home (system/data-home!)))]
    {:file file :state (atom (read-store! file))}))
