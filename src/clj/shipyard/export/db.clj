(ns shipyard.export.db
  "Coherent export snapshots and atomic archive publication."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [shipyard.store.db :as store]
            [clojure.string :as str]
            [shipyard.catalog.db :as catalog]
            [shipyard.export.transforms :as transforms]
            [shipyard.library.index :as index]
            [shipyard.loadout.db :as loadouts]
            [shipyard.part-browser.variants :as variants])
  (:import [java.nio.file Files]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- snapshot-value! [{:keys [catalog loadouts]} selection priority]
  (let [classes (:loadouts (loadouts/snapshot! loadouts))
        records (mapv (fn [[kind id]]
                        (or (when (and (= kind :class)
                                       (= (:library @(:state catalog))
                                          (store/read! (:store catalog)
                                                       #(get-in (d/pull % [{:loadout/library [:library/id]}] [:loadout/id id])
                                                                [:loadout/library :library/id]))))
                              (get classes id))
                            (throw (ex-info "A selected ship is unavailable. Refresh Ship Browser." {})))) selection)]
    (when (empty? records) (throw (ex-info "Select at least one ship to export." {})))
    (let [database (catalog/assembly-snapshot! catalog)]
      {:classes records :database database
       ;; Store source paths use the host separator; ZIP and manifest paths use '/'.
       ;; Convert only that separator so literal backslashes on Unix remain invalid.
       :files (transforms/plan records (:parts database)
                               (mapv #(update % :path str/replace java.io.File/separator "/")
                                     (variants/files! catalog)) priority)})))

(defn snapshot! [{:keys [catalog library] :as deps} selection priority]
  (let [library-lock (:state library) store-lock (:lock (:store catalog))]
    (locking library-lock
      (locking store-lock
        (snapshot-value! deps selection priority)))))

(defn source-path! [root {:keys [path size mtime]}]
  (transforms/source-path path)
  (let [root (fs/real-path root) file (fs/path root path)]
    (when-not (and (fs/regular-file? file)
                   (fs/starts-with? (fs/real-path file) root)
                   (= size (fs/size file))
                   (= mtime (fs/file-time->millis (fs/last-modified-time file))))
      (throw (ex-info (str "Source changed or is outside the library: " path ". Rescan and retry.") {:path path})))
    file))

(defn- csv [value]
  (str "\"" (str/replace (str value) "\"" "\"\"") "\""))

(defn write-archive!
  "Generate a complete temporary ZIP, then publish without replacing an existing file.
  bytes! returns original or transformed bytes and is shared with preview generation."
  [root directory files bytes!]
  (let [root (fs/real-path root)
        _ (when (str/blank? directory) (throw (ex-info "Choose an export directory." {})))
        destination (fs/normalize (fs/absolutize directory))
        ;; Resolve existing ancestors before making directories, including symlink parents.
        ancestor (first (filter fs/exists? (take-while some? (iterate fs/parent destination))))]
    (when (or (fs/starts-with? destination root)
              (and ancestor (fs/starts-with? (fs/real-path ancestor) root)))
      (throw (ex-info "Choose an export directory outside the model library." {})))
    (fs/create-dirs destination)
    (when (fs/starts-with? (fs/real-path destination) root)
      (throw (ex-info "Choose an export directory outside the model library." {})))
    (let [temp (Files/createTempFile (fs/path destination) ".shipyard-export-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))
          target (fs/path destination (str "ships-" (random-uuid) ".zip"))]
      (try
        (with-open [zip (ZipOutputStream. (io/output-stream (fs/file temp)))]
          (doseq [entry files]
            (let [source (source-path! root entry) bytes (bytes! entry source)]
              (source-path! root entry)
              (.putNextEntry zip (ZipEntry. ^String (:archive-path entry)))
              (.write zip ^bytes bytes)
              (.closeEntry zip)))
          (doseq [[directory entries] (sort-by key (group-by :ship-directory files))]
            (.putNextEntry zip (ZipEntry. (str directory "/index.csv")))
            (.write zip (.getBytes (str "path,variant,quantity\r\n"
                                        (str/join "" (map #(str (csv (last (str/split (:archive-path %) #"/"))) "," (name (:variant %)) "," (:quantity %) "\r\n") entries))) "UTF-8"))
            (.closeEntry zip)))
        (doseq [entry files] (source-path! root entry))
        (Files/createLink target temp)
        (str target)
        (finally (Files/deleteIfExists temp))))))

(defn export! [{:keys [catalog library] :as deps} selection priority directory bytes!]
  (let [library-lock (:state library) store-lock (:lock (:store catalog))]
    (locking library-lock
      (locking store-lock
        (let [{:keys [files] :as snapshot} (snapshot! deps selection priority)]
          (write-archive! (index/root! library) directory files (partial bytes! snapshot)))))))
