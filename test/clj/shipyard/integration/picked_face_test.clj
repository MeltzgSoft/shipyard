(ns shipyard.integration.picked-face-test
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
            [shipyard.picked-face-fixture :as parts]
            [shipyard.pitting.geometry :as geometry]
            [shipyard.wire :as wire]))

(defn- bytes! [file]
  (with-open [stream (io/input-stream file)] (.readAllBytes stream)))

(deftest facet-responses-cross-the-event-transport-boundary-without-writing-mounts
  (let [started (fixture/start! false parts/build! (fn [_])) handler (:handler started)
        sys (:system started) library (:shipyard.library/index sys) cat (:shipyard.catalog/db sys)
        source (io/file (str (:root started)) parts/id "unsupported.stl")
        before (bytes! source)]
    (try
      (let [deadline (+ (System/currentTimeMillis) 30000)]
        (loop []
          (let [response (handler (mock/request :get (urls/part-url parts/id)))]
            (when-not (str/includes? (:body response) "detail--ready")
              (when (> (System/currentTimeMillis) deadline) (throw (ex-info "Part preparation timed out" {})))
              (Thread/sleep 25) (recur)))))
      (let [key (index/mesh-key! library parts/id)
            mesh (wire/decode (bytes! (cache/tier-file (:shipyard.mesh/cache sys) key 0)))
            triangles (geometry/mesh-triangles mesh)
            dense (first (keep-indexed #(when (neg? (ffirst %2)) %1) triangles))
            small (first (keep-indexed #(when (pos? (ffirst %2)) %1) triangles))
            post! #(handler (mock/request :post "/facet" {:part-id parts/id :mesh-key key :triangle-index (str %)}))
            selected #(edn/read-string (second (re-find #"name=\"facet-indices\"[^>]*value=\"([^\"]+)\"" (:body %))))]
        (testing "real dense selections use body events, while compact selections retain header events"
          (let [dense-response (post! dense) small-response (post! small)]
            (is (= [200 200] (mapv :status [dense-response small-response])))
            (is (= 800 (count (selected dense-response))))
            (is (= 2 (count (selected small-response))))
            (is (nil? (get-in dense-response [:headers "HX-Trigger"])))
            (is (str/includes? (:body dense-response) "data-viewport-events="))
            (is (str/includes? (:body dense-response) "shipyard:facet-preview"))
            (is (str/includes? (get-in small-response [:headers "HX-Trigger"]) "shipyard:facet-preview"))))
        (testing "picking only produces drafts and preserves source geometry"
          (is (empty? (:part/mounts (:part (catalog/part-context! cat parts/id)))))
          (is (java.util.Arrays/equals ^bytes before ^bytes (bytes! source)))))
      (finally (fixture/stop! started)))))
