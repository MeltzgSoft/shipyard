(ns shipyard.e2e.support
  "A real server, a real browser, a fixture library (TECHNICAL.md §10.3).

  Everything here is hermetic: the library, the mesh cache and the scan index
  all live under one temp directory that is thrown away with the run. An E2E
  suite that writes into `$XDG_CACHE_HOME` would evict the developer's real
  mesh cache and file part ids from a temp tree into their scan index."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [is]]
            [etaoin.api :as e]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [shipyard.fixtures :as f])
  (:import [java.io File]
           [org.eclipse.jetty.server Server ServerConnector]))

;; --- the fixture library ----------------------------------------------------

(def hull-id "Human Navy Fleet Bundle/Cruiser/Cruiser Hull")
(def prow-id "Human Navy Fleet Bundle/Cruiser/Classic Ram Prow")
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

(defn assert-bundle!
  "Fail with the command to run rather than with a twenty-second wait for an
  element that was never going to appear.

  Missing htmx is the interesting one: the page renders, the canvas is there,
  and nothing ever loads the library - which reads exactly like a server bug
  and is not one."
  []
  (doseq [[path fix] required-assets]
    (when-not (fs/regular-file? path)
      (throw (ex-info (str "missing " path " - run `" fix "` first") {:path path})))))

(defn- config [root cache-home]
  {:shipyard.library/index {:root (str root) :cache-home (str cache-home)}
   :shipyard.mesh/cache    {:crease-deg 35 :lod-tiers [1.0 0.25 0.05]
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

(defn- browser-binary
  "Chromium under whichever name this machine installed it as. `SHIPYARD_CHROME`
  wins, so CI can point at its own."
  []
  (or (not-empty (System/getenv "SHIPYARD_CHROME"))
      (first (filter fs/regular-file?
                     ["/usr/bin/google-chrome" "/usr/bin/google-chrome-stable"
                      "/usr/bin/chromium" "/usr/bin/chromium-browser"]))))

(def chrome-args
  "**WebGL in headless Chrome needs software rendering** - a runner with no GPU
  otherwise fails in a way that looks like an application bug (§10.3). ANGLE
  over SwiftShader is that renderer, and since Chrome 128 it must be asked for
  explicitly: without `--enable-unsafe-swiftshader` the context is refused and
  `WebGLRenderer` throws."
  ["--headless=new"
   "--use-gl=angle"
   "--use-angle=swiftshader"
   "--enable-unsafe-swiftshader"
   "--disable-dev-shm-usage"     ; small /dev/shm in a container crashes the tab
   "--no-sandbox"                ; CI runs as root in a container
   "--window-size=1280,900"])

(defn make-driver []
  (e/chrome (cond-> {:args chrome-args}
              (browser-binary) (assoc :path-browser (browser-binary)))))

;; --- polling ----------------------------------------------------------------

(defn wait-until
  "Poll `f` until it returns something truthy. etaoin's own waits are about the
  DOM; the viewport's readiness lives behind `window.__shipyard`."
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
  "The viewport's introspection hook, or nil before it exists. etaoin parses the
  returned object with keyword keys."
  [driver]
  (e/js-execute driver "return window.__shipyard ? window.__shipyard.stats() : null;"))

(defn loaded-parts [driver]
  (some-> (stats driver) :parts vec))

(defn await-part
  "Wait for `part-id` to be on the GPU, and return the stats that prove it."
  [driver part-id]
  (let [s (wait-until #(let [s (stats driver)]
                         (when (some #{part-id} (:parts s)) s)))]
    (is (some? s) (str "part never reached the viewport: " part-id))
    s))
