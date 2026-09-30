(ns shipyard.importer.archive
  "Bounded recursive ZIP extraction. Entry names are evidence, never disk paths."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [shipyard.importer.transforms :as t])
  (:import [java.security MessageDigest]
           [java.util.zip ZipFile]))

(def limits {:depth 12 :entries 100000 :bytes (* 64 1024 1024 1024)})

(defn- copy! [input file budget]
  (let [buffer (byte-array 65536) digest (MessageDigest/getInstance "SHA-256")]
    (with-open [out (io/output-stream file)]
      (loop []
        (let [n (.read ^java.io.InputStream input buffer)]
          (when (pos? n)
            (when (> (:bytes (swap! budget update :bytes + n)) (:bytes limits))
              (throw (ex-info "Archive exceeds the 64 GiB extraction limit." {})))
            (.update digest buffer 0 n)
            (.write out buffer 0 n)
            (recur)))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- walk! [file directory chain budget]
  (when (> (count chain) (:depth limits))
    (throw (ex-info "Archive nesting exceeds 12 levels." {})))
  (with-open [zip (ZipFile. (io/file file))]
    (reduce
     (fn [result ^java.util.zip.ZipEntry entry]
       (when (> (:entries (swap! budget update :entries inc)) (:entries limits))
         (throw (ex-info "Archive contains too many entries." {})))
       (let [n (.getName entry) lower (str/lower-case n)
             zip? (str/ends-with? lower ".zip") stl? (str/ends-with? lower ".stl")]
         (if (or (.isDirectory entry) (not (or zip? stl?))
                 (some #{"__MACOSX"} (t/components n))
                 (str/starts-with? (last (t/components n)) "._"))
           result
           (let [id (str "import-" (random-uuid))
                 target (fs/file directory id)
                 chain (conj chain n)
                 sha (with-open [in (.getInputStream zip entry)] (copy! in target budget))]
             (if zip?
               (try (into result (walk! target directory chain budget))
                    (finally (fs/delete-if-exists target)))
               (conj result {:key id :file target :chain chain :sha sha :variant (t/variant-hint chain)}))))))
     [] (enumeration-seq (.entries zip)))))

(defn extract! [archive directory]
  (when-not (and (fs/regular-file? archive) (str/ends-with? (str/lower-case (str archive)) ".zip"))
    (throw (ex-info "Choose an existing ZIP archive." {})))
  (walk! archive directory [(str (fs/file-name archive))] (atom {:entries 0 :bytes 0})))
