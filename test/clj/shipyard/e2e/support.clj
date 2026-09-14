(ns shipyard.e2e.support
  "A real server, a real browser, a fixture library (TECHNICAL.md §10.3).

  Everything here is hermetic: the library, the mesh cache and the scan index
  all live under one temp directory that is thrown away with the run. An E2E
  suite that writes into `$XDG_CACHE_HOME` would evict the developer's real
  mesh cache and file part ids from a temp tree into their scan index."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [is]]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [shipyard.fixtures :as f])
  (:import [com.microsoft.playwright Browser Browser$NewPageOptions BrowserType$LaunchOptions
            Locator$ScreenshotOptions Page Page$WaitForSelectorOptions
            Playwright]
           [com.microsoft.playwright.options BoundingBox SelectOption]
           [java.io File]
           [org.eclipse.jetty.server Server ServerConnector]))

;; --- the fixture library ----------------------------------------------------

(def hull-id "Human Navy Fleet Bundle/Cruiser/Cruiser Hull")
(def prow-id "Human Navy Fleet Bundle/Cruiser/Classic Ram Prow")
(def mount-plate-id "Human Navy Fleet Bundle/Cruiser/Mount Test Plate")
(def supported-id "Human Navy Fleet Bundle/Cruiser/Supported Only Prow")
(def ork-id "Ork Fleet Bundle/Escort/Ram Ship")

(defn temp-dir ^File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- write-stl! [dir file tris]
  (let [f (io/file dir file)]
    (io/make-parents f)
    (with-open [o (io/output-stream f)] (.write o ^bytes (f/->binary-stl tris)))
    f))

(def prow-offset
  "The prow fixture sits away from the origin on purpose. Every other generated
  solid is centred there, and a camera that never moved would satisfy a framing
  assertion against one of those."
  [10.0 0.0 0.0])

(defn- translate [tris [dx dy dz]]
  (mapv (fn [tri] (mapv (fn [[x y z]] [(+ x dx) (+ y dy) (+ z dz)]) tri)) tris))

(defn library-tree ^File []
  (let [root (temp-dir "shipyard-e2e-lib")]
    (write-stl! (io/file root hull-id) "unsupported.stl" (f/uv-sphere 1.0 10 20))
    (write-stl! (io/file root prow-id) "unsupported.stl" (translate (f/cube 2.0) prow-offset))
    (write-stl! (io/file root mount-plate-id) "unsupported.stl" (f/mount-plate))
    (write-stl! (io/file root supported-id) "supported.stl" (f/cube 1.0))
    (write-stl! (io/file root ork-id) "unsupported.stl" (f/cube 3.0))
    root))

;; --- the server -------------------------------------------------------------

(def required-assets
  "What the page needs on disk before a browser can be pointed at it.

  Both are gitignored build output. The viewport bundle must be the **dev**
  build - only that one defines `window.__shipyard`, which every scene
  assertion reads - and htmx is copied out of node_modules rather than bundled,
  so a broken viewport build cannot take the whole UI down with it (§8)."
  {"resources/public/js/viewport.js" "npx shadow-cljs compile viewport"
   "resources/public/js/htmx.min.js" "cp node_modules/htmx.org/dist/htmx.min.js resources/public/js/"})

(def ^:private viewport-sources
  "Everything the viewport bundle is compiled from."
  ["src/cljs" "src/cljc"])

(defn- newest-source
  "The most recently modified source file, or nil if there are none."
  []
  (->> viewport-sources
       (filter fs/directory?)
       (mapcat #(fs/glob % "**.{cljs,cljc}"))
       (map fs/file)
       (sort-by #(.lastModified ^File %))
       last))

(defn assert-bundle!
  "Fail with the command to run rather than with a twenty-second wait for an
  element that was never going to appear.

  Missing htmx is the interesting one: the page renders, the canvas is there,
  and nothing ever loads the library - which reads exactly like a server bug
  and is not one.

  **And the bundle has to be newer than the source it was built from** (#51).
  It is gitignored, so it survives `git switch`, and a suite that only checked
  for its existence would test one branch's source against another branch's
  bundle. That happened during #49 and reported failures for behaviour the
  checked-out code did not have - which reads as an application bug, and would
  have been worse the other way round: a stale bundle passing tests for code
  that is not there.

  mtime rather than a content hash, because a checkout updates the mtime of
  every file it changes. Switching between branches whose viewport source is
  identical touches nothing and the bundle stays valid; switching to one that
  moved it does, and that is the case worth catching."
  []
  (doseq [[path fix] required-assets]
    (when-not (fs/regular-file? path)
      (throw (ex-info (str "missing " path " - run `" fix "` first") {:path path}))))
  (let [bundle  (fs/file "resources/public/js/viewport.js")
        source  (newest-source)]
    (when (and source (< (.lastModified bundle) (.lastModified ^File source)))
      (throw (ex-info
              (str "stale viewport bundle: " source " is newer than "
                   bundle " - run `npx shadow-cljs compile viewport` first.\n"
                   "The bundle is gitignored, so it survives a branch switch and would "
                   "otherwise be tested against source it was not built from.")
              {:bundle (str bundle) :source (str source)})))))

(defn- config [root cache-home]
  {:shipyard.library/index {:root (str root) :cache-home (str cache-home)}
   :shipyard.mesh/cache    {:crease-deg 35 :lod-tiers [1.0 0.25 0.05]
                            :facet-angle-deg 1.0
                            :facet-plane-epsilon-mm 0.01
                            :cap-bytes 64000000 :cache-home (str cache-home)}
   :shipyard.catalog/db    {:library (ig/ref :shipyard.library/index)}
   :shipyard.http/jobs     {:library (ig/ref :shipyard.library/index)
                            :cache   (ig/ref :shipyard.mesh/cache)}
   :shipyard.http/routes   {:library (ig/ref :shipyard.library/index)
                            :catalog (ig/ref :shipyard.catalog/db)
                            :cache   (ig/ref :shipyard.mesh/cache)
                            :jobs    (ig/ref :shipyard.http/jobs)}
   ;; Port 0: an ephemeral port, so parallel runs and a developer's own server
   ;; on 8080 cannot collide.
   :shipyard.http/server   {:port 0 :host "127.0.0.1"
                            :handler (ig/ref :shipyard.http/routes)}})

(defn start-system! []
  (let [cfg (config (library-tree) (temp-dir "shipyard-e2e-cache"))]
    (ig/load-namespaces cfg)
    (ig/init cfg)))

(defn- block-bundle
  "404 the viewport bundle, and nothing else."
  [handler]
  (fn [req]
    (if (= "/js/viewport.js" (:uri req))
      {:status 404 :headers {"content-type" "text/plain"} :body "blocked"}
      (handler req))))

(defn start-degraded!
  "The app with its viewport bundle blocked (§8, §10.3).

  Blocked at the server rather than through CDP in the browser: a 404 for
  `/js/viewport.js` is exactly what a failed frontend build or a blocking proxy
  looks like from the page's side, and it needs nothing but a ring wrapper.

  Returns `[system server]`; the caller stops both."
  []
  (let [cfg (config (library-tree) (temp-dir "shipyard-e2e-cache"))
        _   (ig/load-namespaces cfg)
        sys (ig/init cfg [:shipyard.http/routes])
        srv (jetty/run-jetty (block-bundle (:shipyard.http/routes sys))
                             {:port 0 :host "127.0.0.1" :join? false})]
    [sys srv]))

(defn server-port ^long [^Server server]
  (.getLocalPort ^ServerConnector (first (.getConnectors server))))

(defn port ^long [system] (server-port (:shipyard.http/server system)))

(defn base-url [system] (str "http://127.0.0.1:" (port system)))

;; --- the browser ------------------------------------------------------------

(def chrome-args
  "**WebGL in headless Chrome needs software rendering** - a runner with no GPU
  otherwise fails in a way that looks like an application bug (§10.3). ANGLE
  over SwiftShader is that renderer, and since Chrome 128 it must be asked for
  explicitly: without `--enable-unsafe-swiftshader` the context is refused and
  `WebGLRenderer` throws."
  ["--use-gl=angle"
   "--use-angle=swiftshader"
   "--enable-unsafe-swiftshader"
   "--disable-dev-shm-usage"     ; small /dev/shm in a container crashes the tab
   "--no-sandbox"])              ; CI runs as root in a container

(defn make-driver
  "A Playwright browser and a page on it.

  **Playwright brings its own browser**, versioned with the library and fetched
  by `playwright install chromium` (#49). The suite used to drive WebDriver,
  which meant a Chrome binary and a chromedriver binary matched to each other
  by hand - twenty-five lines of CI, and no way to run this level locally
  without installing Chrome system-wide.

  Returns a map rather than a bare page: teardown has to close all three
  objects, and the page alone cannot reach the other two."
  []
  (let [pw      (Playwright/create)
        browser (.launch (.chromium pw)
                         (doto (BrowserType$LaunchOptions.)
                           (.setHeadless true)
                           (.setArgs chrome-args)))]
    {:playwright pw
     :browser    browser
     :page       (.newPage browser (doto (Browser$NewPageOptions.)
                                     (.setViewportSize 1280 900)))}))

(defn quit! [{:keys [^Playwright playwright ^Browser browser]}]
  (some-> browser .close)
  (some-> playwright .close))

;; --- driving it -------------------------------------------------------------
;;
;; Thin verbs over Playwright, so a test reads as what it is doing rather than
;; as interop. They are here rather than inline because the suite changed
;; drivers once already and may again; the tests should not have to care.

(defn go! [{:keys [^Page page]} url] (.navigate page url))

(defn wait-visible!
  ([driver sel] (wait-visible! driver sel 20000))
  ([{:keys [^Page page]} sel timeout-ms]
   (.waitForSelector page sel (doto (Page$WaitForSelectorOptions.)
                                (.setTimeout (double timeout-ms))))))

(defn click! [{:keys [^Page page]} sel] (.click page sel))

(defn check! [{:keys [^Page page]} sel] (.check page sel))

(defn click-point! [{:keys [^Page page]} x y]
  (.click (.mouse page) (double x) (double y)))

(defn drag! [{:keys [^Page page]} [x1 y1] [x2 y2]]
  (let [mouse (.mouse page)]
    (.move mouse (double x1) (double y1))
    (.down mouse)
    (.move mouse (double x2) (double y2))
    (.up mouse)))

(defn fill!
  "Type `value` into `sel`, key by key.

  Not `.fill`, which sets the value and dispatches one `input` event. The filter
  form triggers on `keyup changed delay:300ms` (§7), so a value that arrives
  without keystrokes never fires the search - the box shows the text and the
  list never narrows."
  [{:keys [^Page page]} sel value]
  (.pressSequentially (.locator page sel) value))

(defn select-option!
  "Pick an option by its **label**, not its value.

  \"All bundles\" is the empty-value option the filter form emits, so selecting
  by value cannot distinguish it from an unset select."
  [{:keys [^Page page]} sel label]
  (.selectOption page sel (doto (SelectOption.) (.setLabel label))))

(defn count-els [{:keys [^Page page]} sel] (.count (.locator page sel)))

(defn width [{:keys [^Page page]} sel]
  (.-width ^BoundingBox (.boundingBox (.locator page sel))))

(defn text [{:keys [^Page page]} sel] (or (.textContent page sel) ""))

(defn screenshot-el! [{:keys [^Page page]} sel ^File target]
  (.screenshot (.locator page sel)
               (doto (Locator$ScreenshotOptions.) (.setPath (.toPath target)))))

(defn- ->clj
  "Playwright hands back java.util collections; the assertions want Clojure ones
  with keyword keys."
  [x]
  (cond
    (instance? java.util.Map x)  (into {} (map (fn [[k v]] [(keyword (str k)) (->clj v)])) x)
    (instance? java.util.List x) (mapv ->clj x)
    :else                        x))

(defn js
  "Evaluate `expr` in the page and return it as Clojure data.

  Playwright evaluates an **expression or a function**, where WebDriver ran a
  statement body - so these are `() => ...`, not `return ...`."
  [{:keys [^Page page]} expr]
  (->clj (.evaluate page expr)))

;; --- polling ----------------------------------------------------------------

(defn wait-until
  "Poll `f` until it returns something truthy. Playwright's own waits are about
  the DOM; the viewport's readiness lives behind `window.__shipyard`."
  ([f] (wait-until f 30000))
  ([f timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [v (try (f) (catch Exception _ nil))]
         (cond
           v v
           (> (System/currentTimeMillis) deadline) nil
           :else (do (Thread/sleep 100) (recur))))))))

(defn stats
  "The viewport's introspection hook, or nil before it exists."
  [driver]
  (js driver "() => window.__shipyard ? window.__shipyard.stats() : null"))

(defn loaded-parts [driver]
  (some-> (stats driver) :parts vec))

(defn await-part
  "Wait for `part-id` to be on the GPU, and return the stats that prove it."
  [driver part-id]
  (let [s (wait-until #(let [s (stats driver)]
                         (when (some #{part-id} (:parts s)) s)))]
    (is (some? s) (str "part never reached the viewport: " part-id))
    s))
