(ns shipyard.pitting.db
  "Regenerate a sibling -pitted STL from source, then publish complete output."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.stl :as stl]
            [shipyard.pitting.geometry :as geometry])
  (:import [java.nio.file Files CopyOption StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn target-file ^java.io.File [source]
  (let [source (io/file source)]
    (io/file (.getParentFile source) (str/replace (.getName source) #"(?i)\.stl$" "-pitted.stl"))))

(defn save!
  "Generate before committing mount changes. Failed generation preserves both
  durable mounts and prior output; failed publication restores durable mounts."
  [{:keys [catalog library]} part-id mounts expected-revision]
  (let [library-lock (:state library)]
    (locking library-lock
      (let [part (:part (catalog/part-context! catalog part-id))
            previous (:part/mounts part)
            regenerate? (some :mount/cut (concat mounts previous))]
        (if-not regenerate?
          (catalog/save-authoring! catalog part-id {:mounts mounts :expected-revision expected-revision})
          (let [source (or (index/fresh-source-file! library part-id)
                           (throw (ex-info "The source STL changed. Reopen the part before generating cuts." {})))
                target (target-file source)
                _ (doseq [mount mounts :when (:mount/cut mount)]
                    (when (not= (index/mesh-key! library part-id) (get-in mount [:mount/cut :mesh-key]))
                      (throw (ex-info "A cut belongs to a replaced source mesh. Pick its mount face again before regenerating." {}))))
                bytes (if (some :mount/cut mounts)
                        (let [triangles (geometry/subtract (stl/parse-file! source) mounts)]
                          (when (empty? triangles) (throw (ex-info "These cuts remove the entire model. Reduce their dimensions." {})))
                          (geometry/binary-stl triangles))
                        (Files/readAllBytes (.toPath (io/file source))))
                staging (Files/createTempFile (.toPath (.getParentFile target)) ".shipyard-pitted-" ".stl"
                                              (make-array FileAttribute 0))]
            (try
              (with-open [out (io/output-stream (.toFile staging))] (.write out ^bytes bytes))
              (when-not (index/fresh-source-file! library part-id)
                (throw (ex-info "The source STL changed during generation. Reopen the part and retry." {})))
              (let [store-lock (:lock (:store catalog))]
                (locking store-lock
                  (catalog/save-authoring! catalog part-id {:mounts mounts :expected-revision expected-revision})
                  (try
                    (Files/move staging (.toPath target)
                                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                    (catch Exception e
                      (catalog/save-authoring! catalog part-id {:mounts previous})
                      (throw e)))))
              (finally (Files/deleteIfExists staging)))))))))
