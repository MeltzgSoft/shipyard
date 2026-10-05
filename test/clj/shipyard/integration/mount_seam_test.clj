(ns shipyard.integration.mount-seam-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.urls :as urls]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.mount-seam-fixture :as parts]
            [shipyard.pitting.geometry :as geometry]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(defn- form-value [response field]
  (edn/read-string (second (re-find (re-pattern (str "name=\"" field "\"[^>]*value=\"([^\"]+)\""))
                                    (:body response)))))

(deftest authoritative-facet-responses-cross-a-mirrored-internal-seam
  (let [started (fixture/start! false parts/build! (fn [_])) sys (:system started)
        handler (:handler started) library (:shipyard.library/index sys) cat (:shipyard.catalog/db sys)
        source (.toPath (io/file (str (:root started)) parts/id "unsupported.stl"))
        before (Files/readAllBytes source)]
    (try
      (let [deadline (+ (System/currentTimeMillis) 30000)]
        (loop []
          (let [response (handler (mock/request :get (urls/part-url parts/id)))]
            (when-not (str/includes? (:body response) "detail--ready")
              (when (> (System/currentTimeMillis) deadline) (throw (ex-info "Part preparation timed out" {})))
              (Thread/sleep 25) (recur)))))
      (let [key (index/mesh-key! library parts/id)
            mesh (wire/decode (Files/readAllBytes (.toPath (cache/tier-file (:shipyard.mesh/cache sys) key 0))))
            triangles (geometry/mesh-triangles mesh)
            front? #(every? (fn [[_ y _]] (zero? y)) %)
            left (first (keep-indexed #(when (and (front? %2) (some (fn [[x _ _]] (neg? x)) %2)) %1) triangles))
            right (first (keep-indexed #(when (and (front? %2) (some (fn [[x _ _]] (< 0 x 6)) %2)) %1) triangles))
            post! #(handler (mock/request :post "/facet" {:part-id parts/id :mesh-key key :triangle-index (str %)}))
            a (post! left) b (post! right)]
        (testing "the real tier-0 HTTP boundary returns identical complete centered faces"
          (is (= [200 200] (mapv :status [a b])))
          (is (= 8 (count (form-value a "facet-indices"))))
          (is (= (form-value a "facet-indices") (form-value b "facet-indices")))
          (is (= (form-value a "frame") (form-value b "frame")))
          (is (= [0.0 0.0 0.0] (:mount/pos (form-value a "frame"))))
          (is (= [0.0 1.0 0.0] (:mount/axis (form-value a "frame")))))
        (testing "selecting creates no durable mounts and never changes the STL"
          (is (empty? (:part/mounts (:part (catalog/part-context! cat parts/id)))))
          (is (java.util.Arrays/equals ^bytes before ^bytes (Files/readAllBytes source)))))
      (finally (fixture/stop! started)))))
