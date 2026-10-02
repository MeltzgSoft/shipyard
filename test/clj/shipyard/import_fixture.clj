(ns shipyard.import-fixture
  (:require [clojure.java.io :as io]
            [shipyard.fixtures :as fixtures])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn zip-bytes [entries]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[name data] entries]
        (.putNextEntry zip (ZipEntry. name))
        (.write zip ^bytes data)
        (.closeEntry zip)))
    (.toByteArray out)))

(defn archive! [directory]
  (let [file (io/file (str directory) "New Fleet.zip")
        data (fixtures/->binary-stl (fixtures/cube))]
    (with-open [out (io/output-stream file)]
      (.write out ^bytes (zip-bytes [["Cruisers Supported(1).zip" (byte-array 0)]
                                     ["Cruisers Supported.zip"
                                      (zip-bytes [["Original Files/Hull.stl" data]
                                                  ["Supported Files/Hull.stl" data]
                                                  ["Original Files/Prow.stl" data]])]
                                     ["unfinished.zip.part" (.getBytes "ignored")]])))
    file))

(defn invalid-nested-archive! [directory]
  (let [file (io/file (str directory) "Broken Fleet.zip")]
    (with-open [out (io/output-stream file)]
      (.write out ^bytes (zip-bytes [["Hull.stl" (fixtures/->binary-stl (fixtures/cube))]
                                     ["Download.zip" (zip-bytes [["broken.zip" (.getBytes "not a ZIP")]])]])))
    file))
