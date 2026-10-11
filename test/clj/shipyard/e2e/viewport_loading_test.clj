(ns shipyard.e2e.viewport-loading-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.e2e.part-regions-test :as regions]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.http.urls :as urls]
            [shipyard.jobs :as jobs]
            [shipyard.loadout.db :as loadouts]
            [shipyard.loadout-fixture :as lf]
            [shipyard.scheme.db :as schemes]
            [shipyard.scheme.material :as material])
  (:import [com.microsoft.playwright APIResponse Page Route Route$FulfillOptions]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.function Consumer]))

(defn- hold! [driver pattern accept?]
  (let [held (atom [])]
    (.route ^Page (:page driver) pattern
            (reify Consumer
              (accept [_ route]
                (let [^Route route route response (.fetch route)]
                  (if (accept? response)
                    (swap! held conj [route response])
                    (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))))))
    held))

(defn- release! [held]
  (when-let [[^Route route ^APIResponse response] (first @held)]
    (swap! held subvec 1)
    (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response)))))

(defn- held? [driver held]
  (s/wait-until #(do (s/stats driver) (seq @held))))

(defn- loading? [driver]
  (s/js driver "() => !document.querySelector('#viewport-loading').hidden"))

(defn- await-ready! [driver]
  (is (s/wait-until #(not (loading? driver))))
  (is (= "false" (s/js driver "() => document.querySelector('#viewport').getAttribute('aria-busy')"))))

(defn- assert-brush-uncovered! [driver]
  ;; Await rendered presentation, rather than checking before the loading frame.
  (s/js driver "() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
  (is (not (loading? driver)))
  (is (= "false" (s/js driver "() => document.querySelector('#viewport').getAttribute('aria-busy')"))))

(deftest loading-spans-inspector-preparation-and-mesh-delivery
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        part (hold! driver (str "**" (urls/part-url (:bridge fixture/ids))) (constantly true))
        mesh (hold! driver "**/mesh/*.symesh" (constantly true))]
    (try
      (s/go! driver (s/base-url sys))
      (s/js driver "() => {window.originalCanvas=document.querySelector('#viewport');}")
      (.dblclick ^Page (:page driver) ".bulk-orient__row:not([disabled]) .bulk-orient__part:text-is('bridge')")
      (is (held? driver part))
      (s/wait-visible! driver "#viewport-loading[role=status]")
      (is (= "Preparing preview…" (s/text driver "#viewport-loading")))
      (is (= "true" (s/js driver "() => document.querySelector('#viewport').getAttribute('aria-busy')")))
      ;; Admit later backend polls; keep the first real inspector response held.
      (.unroute ^Page (:page driver) (str "**" (urls/part-url (:bridge fixture/ids))))
      (release! part)
      (is (held? driver mesh))
      (is (loading? driver) "The HTTP inspector response does not end binary loading feedback")
      (let [canvas (s/bounds driver "#viewport") overlay (s/bounds driver "#viewport-loading")]
        (is (= canvas overlay) "The overlay covers the canvas, leaving the inspector outside it"))
      (s/screenshot-el! driver ".stage" (io/file "/tmp/shipyard-260-loading.png"))
      (.emulateMedia ^Page (:page driver)
                     (doto (com.microsoft.playwright.Page$EmulateMediaOptions.)
                       (.setReducedMotion com.microsoft.playwright.options.ReducedMotion/REDUCE)))
      (is (= "none" (s/js driver "() => getComputedStyle(document.querySelector('.stage__spinner')).animationName")))
      (is (s/js driver "() => {const r=document.querySelector('#viewport').getBoundingClientRect();return document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)===window.originalCanvas;}"))
      (.unroute ^Page (:page driver) "**/mesh/*.symesh")
      (release! mesh)
      (s/await-part driver (:bridge fixture/ids))
      (await-ready! driver)
      (is (s/js driver "() => document.querySelector('#viewport')===window.originalCanvas"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest backend-polling-keeps-feedback-between-requests-and-clears-on-failure
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        blocker (jobs/scope! (:shipyard.jobs/pool sys))
        entered (CountDownLatch. 2) release (CountDownLatch. 1)
        pattern (str "**" (urls/part-url (:bridge fixture/ids)))]
    (try
      (dotimes [_ 2] (jobs/submit! blocker #(do (.countDown entered) (.await release))))
      (is (.await entered 5 TimeUnit/SECONDS))
      (s/go! driver (s/base-url sys))
      (s/js driver "() => {window.previewRequests=0;window.previewCompletions=0;document.body.addEventListener('htmx:beforeRequest',e=>{if(e.detail.pathInfo.requestPath.startsWith('/part/'))window.previewRequests++;});document.body.addEventListener('htmx:afterRequest',e=>{if(e.detail.pathInfo.requestPath.startsWith('/part/')){window.previewRequests--;window.previewCompletions++;}});}")
      (s/open-part! driver "bridge")
      (is (s/wait-until #(s/js driver "() => window.previewCompletions>=2 && window.previewRequests===0 && !!document.querySelector('#detail [data-viewport-pending]')")))
      (is (loading? driver) "Backend work remains visible between individual polling requests")
      (.route ^Page (:page driver) pattern (reify Consumer (accept [_ route] (.abort ^Route route))))
      (await-ready! driver)
      (.unroute ^Page (:page driver) pattern)
      ;; Reopening is an ordinary user retry; the queued backend job is reused.
      (s/open-part! driver "bridge")
      (s/wait-visible! driver "#viewport-loading")
      (.countDown release)
      (s/await-part driver (:bridge fixture/ids))
      (await-ready! driver)
      (finally (.countDown release) (s/quit! driver) (fixture/stop! started)))))

(deftest overlapping-sources-and-appearance-resources-keep-one-spinner
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        topology (hold! driver "**/paint/preparation/topology**" (constantly true))
        appearance (hold! driver "**/preparation/*/data"
                          #(= "application/vnd.shipyard.appearance" (get (.headers ^APIResponse %) "content-type")))
        mask (lf/detail-layer! sys (:weapon fixture/ids) material/neutral)]
    (try
      (catalog/save-regions! (:shipyard.catalog/db sys) (:weapon fixture/ids)
                             {:version 2 :mesh-key (:mesh-key mask) :revision 1
                              :layers ["Primary" "Secondary"] :layer-definitions {}
                              :faces (zipmap (keys (:faces mask)) (repeat "Secondary"))})
      (loadouts/put! (:shipyard.loadout/db sys)
                     {:loadout/id (random-uuid) :loadout/name "Loading cruiser"
                      :loadout/hull (:hull fixture/ids) :loadout/slots lf/assignments} :create)
      (s/go! driver (s/base-url sys))
      (s/open-class! driver "Loading cruiser")
      (is (s/wait-until #(do (s/stats driver) (= 2 (count @topology)))))
      (s/wait-visible! driver "#viewport-loading")
      (release! topology)
      (is (s/wait-until #(pos? (count (get-in (s/stats driver) [:assembly :slots])))))
      (is (loading? driver) "One source completing does not hide another source's work")
      (.unroute ^Page (:page driver) "**/paint/preparation/topology**")
      (release! topology)
      (is (s/wait-until #(= 13 (count (get-in (s/stats driver) [:assembly :slots])))))
      (schemes/put! (:shipyard.scheme/db sys)
                    {:scheme/id (random-uuid) :scheme/name "Loading palette"
                     :scheme/layers {"Primary" material/neutral "Secondary" (assoc material/neutral :base [1 0 0])}} :create)
      (named/tab! driver "Schemes")
      (s/select-option! driver "#scheme-select select" "Loading palette")
      (is (held? driver appearance))
      (is (loading? driver) "Installed meshes remain pending until authoritative appearance bytes arrive")
      (.unroute ^Page (:page driver) "**/preparation/*/data")
      (release! appearance)
      (is (s/wait-until #(every? :appearance-prepared
                                 (filter (fn [slot] (= (:weapon fixture/ids) (:part-id slot)))
                                         (get-in (s/stats driver) [:assembly :slots])))))
      (await-ready! driver)
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest failed-and-obsolete-mesh-work-does-not-strand-the-spinner
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        mesh (hold! driver "**/mesh/*.symesh" (constantly true))]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "bridge")
      (is (held? driver mesh))
      (s/wait-visible! driver "#viewport-loading")
      (let [[^Route route _] (first @mesh)]
        (reset! mesh [])
        (.abort route))
      (is (s/wait-until #(= "failed" (:status (s/stats driver)))))
      (await-ready! driver)
      (s/open-part! driver "prow")
      (is (held? driver mesh))
      (s/wait-visible! driver "#viewport-loading")
      (workspace/switch! driver "ships")
      (s/wait-visible! driver "#ship-filters")
      (await-ready! driver)
      (.unroute ^Page (:page driver) "**/mesh/*.symesh")
      (release! mesh)
      (await-ready! driver)
      (is (empty? (:parts (s/stats driver))))
      (workspace/switch! driver "browse")
      (s/await-part driver (:prow fixture/ids))
      (await-ready! driver)
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest brush-workflows-retain-their-own-pending-feedback
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "weapon") (s/await-part driver (:weapon fixture/ids))
      (testing "a region stroke stays uncovered while its real save response is held"
        (s/click! driver "[data-detail-tab=regions]")
        (is (s/wait-until #(= "true" (s/js driver "() => document.querySelector('#part-regions').getAttribute('data-region-projection-ready')"))))
        (let [held (hold! driver "**/parts/regions/stroke" (constantly true))]
          (apply brush/stroke! driver (regions/region-point driver 0))
          (is (held? driver held))
          (assert-brush-uncovered! driver)
          (release! held)
          (.unroute ^Page (:page driver) "**/parts/regions/stroke")
          (is (s/wait-until #(= "false" (s/js driver "() => String(document.querySelector('#region-stroke').elements.radius.disabled)"))))))
      (testing "mount trimming stays uncovered during backend boundary preparation"
        (s/click! driver "[data-detail-tab=mounts]")
        (apply s/click-point! driver (regions/region-point driver 0))
        (s/wait-visible! driver ".mount-wizard__form")
        (let [held (hold! driver "**/mounts/preview" (constantly true))]
          (s/check! driver "[data-mount-face-edit]")
          (is (held? driver held))
          (assert-brush-uncovered! driver)
          (release! held)
          (.unroute ^Page (:page driver) "**/mounts/preview")))
      (testing "Customize stays uncovered while saving detail paint"
        (loadouts/put! (:shipyard.loadout/db sys)
                       {:loadout/id (random-uuid) :loadout/name "Brush cruiser"
                        :loadout/hull (:hull fixture/ids) :loadout/slots {}} :create)
        (s/open-class! driver "Brush cruiser")
        (named/tab! driver "Customize")
        (named/create! driver "Brush ship")
        (s/await-assembly-prepared! driver 1)
        (let [held (hold! driver "**/ships/paint/stroke" (constantly true))]
          (apply brush/stroke! driver (brush/face-point driver [] 0))
          (is (held? driver held))
          (assert-brush-uncovered! driver)
          (release! held)
          (.unroute ^Page (:page driver) "**/ships/paint/stroke")
          (brush/await-saved! driver)))
      (finally (s/quit! driver) (fixture/stop! started)))))
