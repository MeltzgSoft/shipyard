(ns shipyard.fleet.export
  "Filesystem boundary for validated, atomically published print bundles."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [digest :as digest]
            [shipyard.fleet.plan :as plan])
  (:import [java.nio.file Files Path LinkOption FileAlreadyExistsException]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(defn- fail! [code message data]
  (throw (ex-info message (merge {:type :fleet-export :code code} data))))

(defn csv-field [value]
  (let [value (str value)]
    (if (re-find #"[,\"\r\n]" value)
      (str "\"" (str/replace value "\"" "\"\"") "\"") value)))

(defn manifest [files]
  (str "path,variant,quantity\r\n"
       (apply str (for [{:keys [path variant quantity]} files]
                    (str (str/join "," (map csv-field [path (name variant) quantity])) "\r\n")))))

(defn- real-path! ^Path [path]
  (.toRealPath (.toPath (io/file (str path))) (make-array LinkOption 0)))

(defn- source-path! ^Path [^Path root {:keys [path]}]
  (when-not (plan/archive-path? path)
    (fail! :invalid-source-path "A selected STL path is invalid." {:path path}))
  (let [resolved (.normalize (.resolve root ^String path))
        real (.toRealPath resolved (make-array LinkOption 0))]
    (when-not (and (.startsWith real root) (Files/isRegularFile real (make-array LinkOption 0)))
      (fail! :invalid-source-path "A selected STL escapes the library or is not a regular file." {:path path}))
    real))

(defn- check-source! [^Path root {:keys [stamp content-hash path] :as file}]
  (let [source (source-path! root file)
        actual {:size (Files/size source)
                :mtime (.toMillis (Files/getLastModifiedTime source (make-array LinkOption 0)))}
        hash (digest/sha-256 (.toFile source))]
    (when-not (and (= stamp actual) (or (nil? content-hash) (= content-hash hash)))
      (fail! :changed-source "A selected STL changed; refresh the library before exporting." {:path path}))
    {:source source :hash hash}))

(defn- destination! ^Path [^Path library destination]
  (when (str/blank? (str destination))
    (fail! :missing-destination "Choose an export directory in Settings." {}))
  (let [requested (.normalize (.toAbsolutePath (.toPath (io/file (str destination)))))]
    ;; Check the nearest existing ancestor before creating any directory.
    (loop [ancestor requested]
      (if (Files/exists ancestor (make-array LinkOption 0))
        (when (.startsWith (.toRealPath ancestor (make-array LinkOption 0)) library)
          (fail! :invalid-destination "The export directory must be outside the STL library." {}))
        (recur (.getParent ancestor))))
    (Files/createDirectories requested (make-array FileAttribute 0))
    (let [real (.toRealPath requested (make-array LinkOption 0))]
      (when (or (.startsWith real library) (not (Files/isWritable real)))
        (fail! :invalid-destination "Choose a writable export directory outside the STL library." {}))
      real)))

(defn- write-archive! [^Path temporary ^Path library files identities]
  (with-open [zip (ZipOutputStream. (io/output-stream (.toFile temporary)))]
    (doseq [[file identity] (map vector files identities)]
      (let [{:keys [source hash]} (check-source! library file)]
        (when-not (= hash (:hash identity))
          (fail! :changed-source "A selected STL changed during export." {:path (:path file)}))
        (.putNextEntry zip (ZipEntry. ^String (:path file)))
        (with-open [input (io/input-stream (.toFile ^Path source))]
          (io/copy input zip))
        (.closeEntry zip)
        (when-not (= hash (:hash (check-source! library file)))
          (fail! :changed-source "A selected STL changed while copying." {:path (:path file)}))))
    (.putNextEntry zip (ZipEntry. "index.csv"))
    (.write zip (.getBytes ^String (manifest files) java.nio.charset.StandardCharsets/UTF_8))
    (.closeEntry zip)))

(defn- validate-archive! [^Path temporary files identities]
  (with-open [zip (ZipFile. (.toFile temporary))]
    (when-not (= (inc (count files)) (.size zip))
      (fail! :invalid-archive "The completed archive is missing entries." {}))
    (doseq [[file identity] (map vector files identities)]
      (with-open [input (.getInputStream zip (.getEntry zip ^String (:path file)))]
        (when-not (= (:hash identity) (digest/sha-256 input))
          (fail! :changed-source "The copied STL bytes changed during export." {:path (:path file)}))))))

(defn- publish! ^Path [^Path directory ^Path temporary]
  (loop []
    (let [target (.resolve directory (str "fleet-" (UUID/randomUUID) ".zip"))
          published? (try
                       ;; A same-filesystem hard link atomically publishes the complete inode,
                       ;; and unlike ATOMIC_MOVE its contract never replaces an existing name.
                       (Files/createLink target temporary)
                       true
                       (catch FileAlreadyExistsException _ false)
                       (catch Exception _
                         (fail! :publication-failed
                                "Could not publish the bundle atomically. Choose a writable export directory on a filesystem that supports hard links."
                                {:directory (str directory)})))]
      (if published? target (recur)))))

(defn export!
  "Export one captured snapshot. Optional revalidate! checks durable references just
  before publication; source metadata and bytes are checked throughout copying."
  ([snapshot library-root export-root] (export! snapshot library-root export-root nil))
  ([snapshot library-root export-root revalidate!]
   (try
     (let [{:keys [ready? files errors]} (plan/print-plan snapshot)]
       (when-not ready? (fail! :not-ready "Resolve the fleet readiness errors before exporting." {:errors errors}))
       (let [library (real-path! library-root)
             destination (destination! library export-root)
             identities (mapv #(check-source! library %) files)
             temporary (Files/createTempFile destination ".shipyard-fleet-" ".tmp" (make-array FileAttribute 0))]
         (try
           (write-archive! temporary library files identities)
           (validate-archive! temporary files identities)
           (when revalidate! (revalidate!))
           (when-not (= destination (destination! library export-root))
             (fail! :changed-destination "The export directory changed during export." {}))
           (doseq [[file identity] (map vector files identities)]
             (when-not (= (:hash identity) (:hash (check-source! library file)))
               (fail! :changed-source "A selected STL changed before publication." {:path (:path file)})))
           (let [published (publish! destination temporary)]
             {:path (str published) :filename (str (.getFileName published))})
           (finally (Files/deleteIfExists temporary)))))
     (catch clojure.lang.ExceptionInfo e (throw e))
     (catch Exception e
       (throw (ex-info "Could not export the fleet; check the source files and export directory permissions."
                       {:type :fleet-export :code :export-failed} e))))))
