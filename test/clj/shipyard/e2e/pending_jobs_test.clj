(ns shipyard.e2e.pending-jobs-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.fixtures :as meshes]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.thumbnail.part :as parts]
            [shipyard.part-browser.thumbnail :as renderer])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest every-import-row-completes-after-navigation-and-browser-disconnection
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (atom (s/make-driver))
        zip (fs/file (:temp started) "Large Fleet.zip")
        data (meshes/->binary-stl (meshes/cube))
        blocker (jobs/scope! (:shipyard.jobs/pool sys))
        entered (CountDownLatch. 2) release (CountDownLatch. 1)
        session! #(importer/session! {:workspace (:shipyard.workspace/db sys)})
        before (catalog/listing! (:shipyard.catalog/db sys))]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes
                            (mapv (fn [n] [(format "Original Files/Part%03d.stl" n) data]) (range 320)))))
      (s/go! @driver (s/base-url sys))
      (s/wait-visible! @driver ".part-thumbnail img")
      (is (s/wait-until #(= {:running 0 :queued 0} (jobs/progress! (get-in sys [:shipyard.thumbnail/cache :scope])))))
      (dotimes [_ 2] (jobs/submit! blocker #(do (.countDown entered) (.await release))))
      (is (.await entered 5 TimeUnit/SECONDS))
      (s/choose-path! @driver ".import-start" zip)
      (s/wait-visible! @driver ".import-review")
      (let [session (session!) scope (get-in session [:thumbnails :scope])
            deps (assoc session :cache (:shipyard.mesh/cache sys) :import-session true)
            ids (mapv :part/id (index/parts! (:library session)))]
        (is (= 320 (count ids)))
        (is (contains? (:sessions @(get-in sys [:shipyard.importer/db :state])) (:id session)))
        (is (= 320 (:accepted (jobs/counts! scope))))
        (is (= 320 (:queued (jobs/progress! scope))))
        (is (< (s/count-els @driver ".part-drawer") 320) "The browser still loads rows in batches")
        (s/wait-visible! @driver "[data-import-progress][data-accepted='320'][data-pending='320']")
        (s/click! @driver "[data-workspace-mode=settings]")
        (s/wait-visible! @driver "#settings-workspace")
        (s/quit! @driver)
        (reset! driver nil)
        (.countDown release)
        (is (s/wait-until #(= 320 (:completed (jobs/counts! scope))) 60000)
            "All accepted rows finish while no browser exists to poll them")
        (is (zero? (:failed (jobs/counts! scope))))
        (is (= before (catalog/listing! (:shipyard.catalog/db sys))))
        (is (every? #(index/mesh-key! (:library session) %) ids))
        (is (every? #(= :ready (:state (parts/request! deps % 1 false))) ids))
        (is (= 320 (:accepted (jobs/counts! scope))) "Reading cached previews admits no further jobs")
        (reset! driver (s/make-driver))
        (s/go! @driver (s/base-url sys))
        (s/click! @driver "[data-workspace-mode=browse]")
        (s/wait-visible! @driver ".import-review")
        (s/wait-visible! @driver "[data-import-progress][data-completed='320']")
        (s/wait-visible! @driver ".part-thumbnail img")
        (s/click! @driver "form[hx-post='/imports/cancel'] button")
        (s/wait-visible! @driver ".import-start")
        (is (nil? (session!)))
        (is (empty? (:sessions @(get-in sys [:shipyard.importer/db :state]))))
        (is (d/closed? (get-in session [:store :conn])))
        (is (not (fs/exists? (:directory session))))
        (is (= before (catalog/listing! (:shipyard.catalog/db sys)))))
      (finally (.countDown release) (when @driver (s/quit! @driver)) (fixture/stop! started)))))

(deftest failed-preview-stops-polling-and-explicit-retry-recovers
  (let [started (fixture/start! true) driver (s/make-driver)
        failed? (atom false) png! renderer/png!]
    (try
      (with-redefs [renderer/png! (fn [& args]
                                    (if (compare-and-set! failed? false true)
                                      (throw (ex-info "Injected transient render failure" {}))
                                      (apply png! args)))]
        (s/go! driver (s/base-url (:system started)))
        (s/wait-visible! driver ".part-thumbnail button:text-is('Retry preview') >> nth=0")
        (is (s/js driver "() => [...document.querySelectorAll('.part-thumbnail')].filter(el=>el.textContent.includes('Preview unavailable')).every(el=>!el.querySelector('[hx-trigger]'))"))
        (loop []
          (let [pending (s/count-els driver ".part-thumbnail button:text-is('Retry preview')")]
            (when (pos? pending)
              (s/click! driver ".part-thumbnail button:text-is('Retry preview') >> nth=0")
              (is (s/wait-until #(< (s/count-els driver ".part-thumbnail button:text-is('Retry preview')") pending)))
              (recur))))
        (s/wait-visible! driver ".part-thumbnail img")
        (is (s/wait-until #(zero? (s/count-els driver ".part-thumbnail button:text-is('Retry preview')")))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest oversized-import-shows-backpressure-without-partial-review
  (let [started (fixture/start! true fixture/library! fixture/author!
                                {:shipyard.jobs/pool {:threads 1 :queue-size 1}})
        sys (:system started) driver (s/make-driver) before (catalog/listing! (:shipyard.catalog/db sys))]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver ".import-start")
      (s/choose-path! driver ".import-start" (archives/archive! (:temp started)))
      (s/wait-visible! driver "#import-status[role=alert]")
      (is (.contains ^String (s/text driver "#import-status") "Import preview queue is full"))
      (is (zero? (s/count-els driver ".import-review")))
      (is (nil? (importer/session! {:workspace (:shipyard.workspace/db sys)})))
      (is (empty? (:sessions @(get-in sys [:shipyard.importer/db :state]))))
      (is (empty? @(get-in sys [:shipyard.thumbnail/cache :children])))
      (is (= before (catalog/listing! (:shipyard.catalog/db sys))))
      (s/open-part! driver "hull")
      (s/await-part driver (:hull fixture/ids))
      (is (= "loaded" (:status (s/stats driver))))
      (finally (s/quit! driver) (fixture/stop! started)))))
