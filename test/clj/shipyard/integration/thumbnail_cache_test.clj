(ns shipyard.integration.thumbnail-cache-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.fixtures :as fixtures]
            [shipyard.http.jobs :as jobs]
            [shipyard.jobs :as workers]
            [shipyard.library.index :as index]
            [shipyard.loadout.db :as classes]
            [shipyard.loadout-fixture :as lf]
            [shipyard.mesh.cache :as meshes]
            [shipyard.paint.strokes :as strokes]
            [shipyard.scheme.db :as schemes]
            [shipyard.ship.db :as ships]
            [shipyard.http.urls :as urls]
            [shipyard.part-browser.thumbnail :as renderer]
            [shipyard.part.orientation :as orientation]
            [shipyard.thumbnail.cache :as cache]
            [shipyard.thumbnail.transforms :as t]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]
           [java.util.concurrent CountDownLatch TimeUnit]
           [javax.imageio ImageIO]))

(defn await! [f]
  (let [deadline (+ (System/currentTimeMillis) 20000)]
    (loop []
      (let [result (f)]
        (cond result result
              (< (System/currentTimeMillis) deadline) (do (Thread/sleep 25) (recur))
              :else (throw (ex-info "Thumbnail did not become ready" {})))))))

(defn image-url! [handler path]
  (await! #(second (re-find #"src=\"([^\"]+)\"" (:body (handler (mock/request :get path)))))))

(def triangle {:positions [0 0 0 1 0 0 0 1 0] :indices [0 1 2]})

(deftest bounded-parallel-work-deduplication-and-restart-reuse
  (let [started (fixture/start!) sys (:system started) previews (:shipyard.thumbnail/cache sys)
        entered (CountDownLatch. 2) release (CountDownLatch. 1) calls (atom [])
        png (renderer/png! triangle nil)
        render (fn [id] #(do (swap! calls conj id) (.countDown entered)
                             (.await release 10 TimeUnit/SECONDS) png))]
    (try
      (doseq [id [1 2 3 1]] (cache/request! previews {:test id} (render id)))
      (is (.await entered 5 TimeUnit/SECONDS) "Two distinct PNGs render concurrently")
      (is (= #{1 2} (set @calls)) "The third render queues; duplicates share work")
      (is (= {:running 2 :queued 1} (workers/progress! (:scope previews))))
      (.countDown release)
      (doseq [id [1 2 3]] (await! #(cache/file! previews (t/cache-key {:test id}))))
      (is (= [1 2 3] (sort @calls)))
      (await! #(= {:running 0 :queued 0} (workers/progress! (:scope previews))))
      (let [reopened (ig/init-key :shipyard.thumbnail/cache {:cache (:shipyard.mesh/cache sys) :workers (:shipyard.jobs/pool sys) :cap-bytes 134217728})]
        (try
          (doseq [id [1 2 3]]
            (is (= :ready (:state (cache/request! reopened {:test id} #(throw (ex-info "Cache should survive restart" {})))))))
          (is (= 128 (.getWidth (ImageIO/read (cache/file! reopened (t/cache-key {:test 1}))))))
          (finally (ig/halt-key! :shipyard.thumbnail/cache reopened))))
      (finally (.countDown release) (fixture/stop! started)))))

(deftest cheap-references-reuse-content-images-and-survive-restart
  (let [started (fixture/start!) sys (:system started) previews (:shipyard.thumbnail/cache sys)
        png (renderer/png! triangle nil) inputs {:test :existing-png} calls (atom 0)
        prepare! #(do (swap! calls inc) {:inputs inputs :render! (fn [] (throw (ex-info "Must reuse PNG" {})))})
        ready! (fn [cache stamp]
                 (await! #(let [r (cache/request-derived! cache stamp prepare!)]
                            (when (= :ready (:state r)) r))))]
    (try
      (cache/request! previews inputs (constantly png))
      (await! #(cache/file! previews (t/cache-key inputs)))
      (is (= (t/cache-key inputs) (:key (ready! previews {:stamp 1}))))
      (is (= 1 @calls))
      (let [reopened (ig/init-key :shipyard.thumbnail/cache {:cache (:shipyard.mesh/cache sys)
                                                             :workers (:shipyard.jobs/pool sys) :cap-bytes 134217728})]
        (try
          (is (= :ready (:state (cache/request-derived! reopened {:stamp 1} prepare!))))
          (is (= 1 @calls) "Reload and restart avoid both dense input reads and rendering")
          (is (= (t/cache-key inputs) (:key (ready! reopened {:stamp 2}))))
          (is (= 2 @calls) "A changed stamp resolves content again, reusing identical pixels")
          (finally (ig/halt-key! :shipyard.thumbnail/cache reopened))))
      (finally (fixture/stop! started)))))

(deftest disk-images-have-immutable-urls-and-invalidate-after-part-edits
  (let [started (fixture/start!) handler (:handler started) sys (:system started)
        previews (:shipyard.thumbnail/cache sys) cat (:shipyard.catalog/db sys)
        id (:prow fixture/ids) path (str "/thumbnails/" (urls/encode-id id))]
    (try
      (is (= 400 (:status (handler (mock/request :get "/thumbnail-images/invalid")))))
      (is (= 404 (:status (handler (mock/request :get (str "/thumbnail-images/" (apply str (repeat 64 "a"))))))))
      (let [url (image-url! handler path)
            response (handler (mock/request :get url))
            file (:body response)]
        (is (= 200 (:status response)))
        (is (= "image/png" (get-in response [:headers "Content-Type"])))
        (is (= "public, max-age=31536000, immutable" (get-in response [:headers "Cache-Control"])))
        (is (fs/regular-file? file))
        (is (= 128 (.getWidth (ImageIO/read ^java.io.File file))))
        (is (= url (image-url! handler path)))
        (catalog/save-metadata! cat [{:id id :attribute :part/name-override :value "New name"}])
        (is (= url (image-url! handler path)) "Nonvisual metadata reuses the PNG")
        (catalog/save-part-orientation! cat id (orientation/from-euler-degrees 45 0 0))
        (is (not= url (image-url! handler path)))
        (catalog/save-part-orientation! cat id orientation/identity-quaternion)
        (is (= url (image-url! handler path)) "Undo reuses the prior PNG")
        (fs/delete file)
        (is (= url (image-url! handler path)) "Clearing derived files regenerates safely")
        (is (fs/regular-file? file))
        (is (empty? (fs/glob (:dir previews) "*.tmp")))
        (with-open [out (io/output-stream (fs/file (:root started) id "unsupported.stl"))]
          (.write out ^bytes (fixtures/->binary-stl (fixtures/cube 2.0))))
        (let [library (:shipyard.library/index sys)]
          (index/set-root! library (str (:root started)))
          (catalog/reingest! cat (index/parts! library) (index/root! library))
          (jobs/clear! (:shipyard.http/jobs sys)))
        (is (not= url (image-url! handler path)) "Rescanning a changed STL invalidates the PNG"))
      (finally (fixture/stop! started)))))

(deftest dense-regions-use-small-cache-stamps-and-invalidate-on-replacement
  (let [started (fixture/start!) handler (:handler started) sys (:system started)
        cat (:shipyard.catalog/db sys) id (:prow fixture/ids)
        path (str "/thumbnails/" (urls/encode-id id))]
    (try
      (image-url! handler path)
      (let [mesh-key (index/mesh-key! (:shipyard.library/index sys) id)
            regions {:version 2 :mesh-key mesh-key :revision 0 :layer-definitions {}
                     :layers ["Primary" "Secondary"]
                     :faces (zipmap (map #(format "%072x" %) (range 10000)) (repeat "Secondary"))}]
        (catalog/save-regions! cat id regions)
        (let [stamp (:stamp (catalog/thumbnail-context! cat id))
              url (image-url! handler path)]
          (is (< (count (pr-str stamp)) 1000) "Cache lookups do not materialize face assignments")
          (is (= url (second (re-find #"src=\"([^\"]+)\"" (:body (handler (mock/request :get path))))))
              "An unchanged preview returns its cached image immediately")
          (catalog/save-regions! cat id (assoc regions :faces {}))
          (is (not= stamp (:stamp (catalog/thumbnail-context! cat id))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source changed"
                                (catalog/thumbnail-context! cat id stamp)))
          (is (not= url (image-url! handler path)))))
      (finally (fixture/stop! started)))))

(deftest assembly-dependencies-invalidate-class-and-named-ship-previews
  (let [started (fixture/start!) handler (:handler started) sys (:system started)
        cat (:shipyard.catalog/db sys) class-db (:shipyard.loadout/db sys)
        scheme-db (:shipyard.scheme/db sys) ship-db (:shipyard.ship/db sys)
        record {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}
        scheme {:scheme/id (random-uuid) :scheme/name "Fleet colors" :scheme/layers {"Primary" {:base [1.0 0.0 0.0] :metalness 0.2 :roughness 0.6}}}
        ship {:ship/id (random-uuid) :ship/name "Resolute" :ship/class (:loadout/id record) :ship/scheme (:scheme/id scheme) :ship/paint {}}
        class-path (str "/ship-thumbnails/class/" (:loadout/id record))
        ship-path (str "/ship-thumbnails/ship/" (:ship/id ship))]
    (try
      (classes/put! class-db record :create)
      (schemes/put! scheme-db scheme :create)
      (ships/put! ship-db ship :create)
      (let [class-url (image-url! handler class-path) ship-url (image-url! handler ship-path)]
        (is (not= class-url ship-url))
        (is (= ship-url (image-url! handler ship-path)))
        (classes/put! class-db (assoc record :loadout/slots {}) :update)
        (is (not= class-url (image-url! handler class-path)))
        (is (not= ship-url (image-url! handler ship-path)) "Named ships follow saved class edits")
        (classes/put! class-db record :update)
        (is (= class-url (image-url! handler class-path)))
        (catalog/save-mounts! cat (:hull lf/draft)
                              (mapv #(update % :mount/pos (fn [[x y z]] [(+ x 3.0) y z])) fixture/hull-mounts))
        (is (not= class-url (image-url! handler class-path)) "Mount placement changes invalidate assemblies")
        (catalog/save-mounts! cat (:hull lf/draft) fixture/hull-mounts)
        (is (= class-url (image-url! handler class-path)))
        (schemes/put! scheme-db (assoc-in scheme [:scheme/layers "Primary" :base] [0.0 0.0 1.0]) :update)
        (is (not= ship-url (image-url! handler ship-path)) "Shared scheme edits invalidate named ships")
        (let [scheme-url (image-url! handler ship-path)
              part-id (:hull lf/draft)
              mesh-key (index/mesh-key! (:shipyard.library/index sys) part-id)
              mesh (wire/decode (Files/readAllBytes (fs/path (meshes/tier-file (:shipyard.mesh/cache sys) mesh-key 0))))
              detail {:part-id part-id :mesh-key mesh-key
                      :faces (zipmap (strokes/mesh-faces mesh)
                                     (repeat {:base [0.0 1.0 0.0] :metalness 0.2 :roughness 0.6}))}]
          (is (nil? (:error (ships/put! ship-db (assoc-in ship [:ship/paint :paint/details []] detail) :update))))
          (is (not= scheme-url (image-url! handler ship-path)) "Source-bound custom details invalidate the compact ship stamp"))
        (is (= class-url (image-url! handler class-path)) "Unpainted class image remains reusable"))
      (finally (fixture/stop! started)))))

(deftest eviction-and-failed-renders-leave-no-partial-pngs
  (let [started (fixture/start!) png (renderer/png! triangle nil)
        previews (assoc (get-in started [:system :shipyard.thumbnail/cache]) :cap-bytes (* 2 (alength ^bytes png)))
        render! (fn [id]
                  (cache/request! previews {:test id} (constantly png))
                  (await! #(cache/file! previews (t/cache-key {:test id}))))]
    (try
      (let [a (render! 1) b (render! 2)]
        (fs/set-last-modified-time a 2000)
        (fs/set-last-modified-time b 1000)
        (render! 3)
        (is (fs/regular-file? a))
        (is (not (fs/exists? b))))
      (cache/request! previews :broken #(throw (ex-info "Injected render failure" {})))
      (is (= :failed (:state (await! #(let [r (cache/request! previews :broken (constantly png))]
                                        (when (= :failed (:state r)) r))))))
      (is (nil? (cache/file! previews (t/cache-key :broken))))
      (is (empty? (fs/glob (:dir previews) "*.tmp")))
      (finally (fixture/stop! started)))))
