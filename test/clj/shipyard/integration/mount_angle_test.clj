(ns shipyard.integration.mount-angle-test
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.mount-angle-fixture :as curved]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.preparation :as preparation]
            [shipyard.paint.faces :as faces]
            [shipyard.pitting.geometry :as geometry]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(deftest angular-transport-and-durable-selection
  (let [started (fixture/start! false curved/build-library! (fn [_] nil))
        sys (:system started) handler (:handler started)
        library (:shipyard.library/index sys) cat (:shipyard.catalog/db sys)
        mesh-cache (:shipyard.mesh/cache sys)
        key (:mesh-key (cache/ensure! mesh-cache (index/fresh-source-file! library curved/id)))
        _ (index/record-mesh-key! library curved/id key nil)
        mesh (wire/decode (Files/readAllBytes (fs/path (cache/tier-file mesh-cache key 0))))
        seed (first (keep-indexed #(when (= (faces/face-key %2) (faces/face-key (first curved/triangles))) %1)
                                  (geometry/mesh-triangles mesh)))
        post! (fn [uri params] (handler (mock/request :post uri params)))
        pick {:part-id curved/id :mesh-key key :triangle-index (str seed) :face-angle "50"}]
    (try
      (doseq [angle ["-1" "91" "NaN" "Infinity" "bad"]]
        (is (= 400 (:status (post! "/facet" (assoc pick :face-angle angle))))))
      (let [response (post! "/facet" pick)
            event (edn/read-string (get (json/read-str (get-in response [:headers "HX-Trigger"])) "shipyard:facet-preview"))
            indices (:facet-indices event)
            form {:part-id curved/id :mesh-key key :mount-id "ball" :kind "plug" :action "create"
                  :frame (pr-str (:frame event)) :facet-indices (pr-str indices)
                  :face-seed (str seed) :face-angle "50"}]
        (let [body {:part-id curved/id :mesh-key key :owner "angular-trim" :sequence 1
                    :frame (select-keys (:frame event) [:mount/pos :mount/axis :mount/roll])
                    :indices (vec (remove #{seed} indices)) :border-indices indices
                    :face-angle 50 :face-seed seed :capacity 1 :direction :horizontal}
              request (fn [value] (-> (mock/request :post "/mounts/preview")
                                      (mock/content-type "application/edn") (mock/body (pr-str value))))
              response (handler (request body))
              resource (:resource (edn/read-string (:body response)))
              service (:shipyard.preparation/service sys)
              deadline (+ (System/currentTimeMillis) 10000)
              state (loop []
                      (let [entry (preparation/status! service resource)]
                        (if (and (= :running (:state entry)) (< (System/currentTimeMillis) deadline))
                          (do (Thread/sleep 20) (recur)) entry)))]
          (is (= 200 (:status response)))
          (is (= :ready (:state state)))
          (is (= 400 (:status (handler (request (assoc body :face-angle 91)))))))
        (is (= 200 (:status response)))
        (is (= 8 (count indices)))
        (is (= 422 (:status (post! "/mounts" (assoc form :face-angle "1")))))
        (is (= 200 (:status (post! "/mounts" form))))
        (let [saved (first (:part/mounts (:part (catalog/part-context! cat curved/id))))]
          (is (= {:mesh-key key :indices indices :seed seed :angle-deg 50.0} (:mount/facet saved)))))
      (is (= 409 (:status (post! "/facet" (assoc pick :mesh-key (apply str (repeat 64 "0")))))))
      (finally (fixture/stop! started)))))
