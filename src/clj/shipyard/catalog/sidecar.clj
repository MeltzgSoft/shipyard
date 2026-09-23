(ns shipyard.catalog.sidecar
  "Per-part `shipyard.edn`, stored inside the part folder (TECHNICAL.md §1.3).

  Mounts live beside the STL they describe so they survive a library
  reorganisation, diff per-part rather than as one churning central file, and
  travel with a part folder that gets copied elsewhere."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [shipyard.system :as system])
  (:import [java.io File]))

(def filename "shipyard.edn")
(def ^:const format-version 1)

(defn sidecar-file ^File [root part-id] (io/file root part-id filename))

(defn read-sidecar!
  "Read a part's sidecar, or nil when it has none.

  A malformed sidecar throws. It must never degrade to \"this part has no
  mounts\": that looks identical to an unauthored part, so a syntax error would
  silently discard work and the user would find out by re-doing it."
  [root part-id]
  (let [f (sidecar-file root part-id)]
    (when (.isFile f)
      (try
        (edn/read-string (slurp f))
        (catch Exception e
          (throw (ex-info (str "malformed sidecar: " (.getPath f)
                               " - fix or delete it; refusing to treat it as having no mounts")
                          {:file (.getPath f) :part-id part-id} e)))))))

(defn write-sidecar!
  "Write a part's sidecar atomically. Callers write here BEFORE transacting, so
  a failed transact leaves the data safe on disk (§1.2)."
  [root part-id data]
  (let [f (sidecar-file root part-id)]
    (system/write-atomically! f (pr-str (assoc data :shipyard/version format-version)))
    f))

(defn update-sidecar!
  "Read, apply `f`, write back. The durable half of a write-through update."
  [root part-id f & args]
  (write-sidecar! root part-id (apply f (or (read-sidecar! root part-id) {}) args)))

(defn update-sidecars!
  "Preflight every existing sidecar, then update them with rollback on failure.
  The caller serializes catalog mutations and publishes its index after success."
  [root updates]
  (let [files (mapv (fn [[part-id update-fn]]
                      (let [file (sidecar-file root part-id)]
                        (when-not (.isFile file)
                          (throw (ex-info "Part sidecar must be a regular file" {:part-id part-id})))
                        (let [before (slurp file)]
                          {:file file :before before
                           :after (update-fn (edn/read-string before))}))) updates)
        written (atom [])]
    (try
      (doseq [{:keys [file after] :as entry} files]
        (system/write-atomically! file (pr-str (assoc after :shipyard/version format-version)))
        (swap! written conj entry))
      (catch Exception e
        (doseq [{:keys [file before]} (reverse @written)]
          (system/write-atomically! file before))
        (throw e)))))
