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
            [shipyard.mesh.facet :as facet]
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
        pick {:part-id curved/id :mesh-key key :triangle-index (str seed) :face-angle "20"}]
    (try
      (doseq [angle ["-1" "91" "NaN" "Infinity" "bad"]]
        (is (= 400 (:status (post! "/facet" (assoc pick :face-angle angle))))))
      (doseq [seam ["-1" "0.101" "NaN" "Infinity" "bad"]]
        (is (= 400 (:status (post! "/facet" (assoc pick :face-seam seam))))))
      (let [response (post! "/facet" pick)
            event (edn/read-string (get (json/read-str (get-in response [:headers "HX-Trigger"])) "shipyard:facet-preview"))
            indices (:facet-indices event)
            form {:part-id curved/id :mesh-key key :mount-id "ball" :kind "plug" :action "create"
                  :frame (pr-str (:frame event)) :facet-indices (pr-str indices)
                  :face-seed (str seed) :face-angle "20"}]
        (let [body {:part-id curved/id :mesh-key key :owner "angular-trim" :sequence 1
                    :frame (select-keys (:frame event) [:mount/pos :mount/axis :mount/roll])
                    :indices (vec (remove #{seed} indices)) :border-indices indices
                    :face-angle 20 :face-seed seed :capacity 1 :direction :horizontal}
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
        (is (= 12 (count indices)))
        (is (= 422 (:status (post! "/mounts" (assoc form :face-angle "1")))))
        (is (= 200 (:status (post! "/mounts" form))))
        (let [saved (first (:part/mounts (:part (catalog/part-context! cat curved/id))))]
          (is (= {:mesh-key key :indices indices :seed seed :angle-deg 20.0 :seam-mm 0.005 :angle-rule :adjacent} (:mount/facet saved)))))
      (is (= 409 (:status (post! "/facet" (assoc pick :mesh-key (apply str (repeat 64 "0")))))))
      (finally (fixture/stop! started)))))

(defn- hidden-value [response field]
  (second (re-find (re-pattern (str "name=\"" field "\"[^>]*value=\"([^\"]*)\"")) (:body response))))

(deftest legacy-seed-bounded-mounts-remain-editable
  (let [started (fixture/start! false curved/build-library! (fn [_]))
        sys (:system started) handler (:handler started) cat (:shipyard.catalog/db sys)
        library (:shipyard.library/index sys) mesh-cache (:shipyard.mesh/cache sys)
        key (:mesh-key (cache/ensure! mesh-cache (index/fresh-source-file! library curved/id)))
        _ (index/record-mesh-key! library curved/id key nil)
        mesh (wire/decode (Files/readAllBytes (fs/path (cache/tier-file mesh-cache key 0))))
        old (facet/select mesh 0 {:selection-angle-deg 25 :selection-rule :seed})
        mount (merge (:frame old) {:mount/id :legacy :mount/kind :plug :mount/origin :picked :mount/capacity 1
                                   :mount/facet {:mesh-key key :indices (:facet-indices old) :seed 0 :angle-deg 25.0}})
        post! (fn [uri params] (handler (mock/request :post uri params)))]
    (try
      (catalog/save-mounts! cat curved/id [mount])
      (let [edit (post! "/mounts/edit" {:part-id curved/id :mount-id "legacy"})
            form (into {:part-id curved/id :mount-id "legacy" :kind "plug" :action "update"}
                       (map (fn [field] [(keyword field) (hidden-value edit field)]))
                       ["frame" "mesh-key" "facet-indices" "face-seed" "face-angle" "face-seam" "face-rule" "original-mount-id"])]
        (is (= "seed" (hidden-value edit "face-rule")))
        (is (= 200 (:status (post! "/mounts" form))))
        (let [saved (first (:part/mounts (:part (catalog/part-context! cat curved/id))))]
          (is (= (:facet-indices old) (get-in saved [:mount/facet :indices])))
          (is (= :seed (get-in saved [:mount/facet :angle-rule]))))
        (let [reselect (post! "/facet" {:part-id curved/id :mesh-key key :triangle-index "0" :face-angle "25"
                                        :face-rule "seed" :original-mount-id "legacy" :reselect "true"})]
          (is (= "adjacent" (hidden-value reselect "face-rule")))
          (is (= 12 (count (edn/read-string (hidden-value reselect "facet-indices")))))))
      (finally (fixture/stop! started)))))

(deftest default-pick-uses-the-displayed-neighboring-angle
  (let [started (fixture/start! false #(curved/build-library! % curved/gentle-triangles) (fn [_]))
        sys (:system started) handler (:handler started) library (:shipyard.library/index sys)
        mesh-cache (:shipyard.mesh/cache sys)
        key (:mesh-key (cache/ensure! mesh-cache (index/fresh-source-file! library curved/id)))]
    (try
      (index/record-mesh-key! library curved/id key nil)
      (let [response (handler (mock/request :post "/facet" {:part-id curved/id :mesh-key key :triangle-index "0"}))]
        (is (= 200 (:status response)))
        (is (= 12 (count (edn/read-string (hidden-value response "facet-indices")))))
        (is (= "adjacent" (hidden-value response "face-rule"))))
      (finally (fixture/stop! started)))))
