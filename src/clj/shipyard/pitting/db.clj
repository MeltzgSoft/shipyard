(ns shipyard.pitting.db
  "Regenerate a sibling -pitted STL from source, then publish complete output."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.store.db :as store]
            [shipyard.mesh.stl :as stl]
            [shipyard.pitting.geometry :as geometry])
  (:import [java.nio.file Files CopyOption StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn target-file ^java.io.File [source]
  (let [source (io/file source)]
    (io/file (.getParentFile source) (str/replace (.getName source) #"(?i)\.stl$" "-pitted.stl"))))

(defn- replace-file! [source target]
  (Files/move source target
              (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING])))

(defn- publish! [{:keys [catalog library]} part-id mounts revision staging target]
  (let [database (:store catalog)
        path (.toPath ^java.io.File target)
        backup (when (.isFile ^java.io.File target)
                 (Files/createTempFile (.getParent path) ".shipyard-pitted-previous-" ".stl"
                                       (make-array FileAttribute 0)))
        published? (volatile! false)
        keep-backup? (volatile! false)]
    (try
      (when backup
        (Files/copy path backup ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/COPY_ATTRIBUTES])))
      (store/write! database
                    (fn [conn]
                      (let [transaction (assoc catalog :store (assoc database :conn conn))]
                        (catalog/save-authoring! transaction part-id {:mounts mounts :expected-revision revision})
                        (replace-file! staging path)
                        (vreset! published? true)
                        (catalog/observe-source! transaction part-id
                                                 {:variant :unsupported-pitted
                                                  :relative (str (fs/relativize (index/root! library) target))
                                                  :size (Files/size path)
                                                  :mtime (.toMillis (Files/getLastModifiedTime path (make-array java.nio.file.LinkOption 0)))}))))
      (catch Throwable failure
        (when @published?
          (try
            (if backup (replace-file! backup path) (Files/deleteIfExists path))
            (catch Throwable rollback
              (vreset! keep-backup? true)
              (.addSuppressed failure rollback))))
        (throw failure))
      (finally (when (and backup (not @keep-backup?)) (Files/deleteIfExists backup))))
    (index/record-variant! library part-id :unsupported-pitted)))

(defn save!
  "Generate before committing mount changes. Failed generation preserves both
  durable mounts and prior output. Publish mounts and observed variant facts in
  one transaction; compensate output replacement if that transaction fails."
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
              (publish! {:catalog catalog :library library} part-id mounts expected-revision staging target)
              (finally (Files/deleteIfExists staging)))))))))
