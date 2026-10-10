(ns shipyard.integration.picking-preparation-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.topology :as topology]
            [shipyard.preparation :as preparation]
            [shipyard.integration.thumbnail-cache-test :as previews]
            [shipyard.wire :as wire])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]))

(deftest prepared-picking-positions-retain-tier-zero-ordinal-identity
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        library (:shipyard.library/index sys) id (:weapon fixture/ids)
        mesh-key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! library id)))
        service (:shipyard.preparation/service sys)]
    (try
      (index/record-mesh-key! library id mesh-key nil)
      (let [request (mock/request :get "/paint/preparation/topology" {:part-id id :mesh-key mesh-key})
            resource (:resource (edn/read-string (:body (handler request))))
            data-url (str "/preparation/" resource "/data")]
        (previews/await! #(= :ready (:state (preparation/status! service resource))))
        (testing "Backend nonindexed draw vertices map gl_VertexID/3 to exact source triangles"
          (let [bytes (:body (handler (mock/request :get data-url)))
                buffer (.order (ByteBuffer/wrap bytes) ByteOrder/LITTLE_ENDIAN)
                source (wire/decode (Files/readAllBytes (fs/path (cache/tier-file (:shipyard.mesh/cache sys) mesh-key 0))))]
            (is (= 1 (.getInt buffer)))
            (is (= 12 (.getInt buffer)))
            (doseq [triangle (range 12)]
              (is (= (vec (mapcat identity (topology/triangle-points source triangle)))
                     (vec (repeatedly 9 #(.getFloat buffer))))))))
        (testing "Repeated requests reuse immutable draw data; changed sources invalidate delivery"
          (is (= resource (:resource (edn/read-string (:body (handler request))))))
          (spit (index/fresh-source-file! library id) "changed source")
          (is (= 410 (:status (handler (mock/request :get data-url)))))))
      (finally (fixture/stop! started)))))
