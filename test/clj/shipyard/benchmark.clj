(ns shipyard.benchmark
  "On-demand measurements for the M1 performance budgets (issue #44).

  This is deliberately not a test or a CI gate. It lives on the test source
  path so its optional Playwright dependency cannot leak into the shipped jar.
  Wall time and frame rate belong
  to the machine and model library they were measured on; the command records
  both beside the results so the numbers remain evidence rather than folklore.

  Every cache and scan index is created below the JVM temp directory. The model
  library is read-only."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [integrant.core :as ig]
            [shipyard.canary :as canary]
            [shipyard.library.index :as index]
            [shipyard.library.scan :as scan]
            [shipyard.mesh.cache :as cache]
            [shipyard.mesh.stl :as stl]
            [shipyard.system :as system])
  (:import [com.microsoft.playwright Browser Browser$NewPageOptions
            BrowserType$LaunchOptions Page Playwright]
           [java.io File]
           [java.lang.management ManagementFactory]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration Instant]
           [java.util Map List]
           [org.eclipse.jetty.server Server ServerConnector]))

(def ^:const mib (* 1024 1024))
(def ^:const default-runs 3)
(def ^:const default-viewport-seconds 10)
(def ^:const cruiser-id "Human Navy Fleet Bundle/Cruiser/Hull")

;; --- pure result shaping ----------------------------------------------------

(defn median
  "Median of a non-empty numeric collection."
  [xs]
  (let [values (vec (sort xs))
        n      (count values)
        middle (quot n 2)]
    (when (pos? n)
      (if (odd? n)
        (double (values middle))
        (/ (+ (double (values (dec middle))) (double (values middle))) 2.0)))))

(defn summarize
  "Stable summary for repeated wall-clock samples."
  [xs]
  (when (seq xs)
    {:runs      (count xs)
     :minimum   (double (apply min xs))
     :median    (median xs)
     :maximum   (double (apply max xs))
     :samples   (mapv double xs)}))

(defn parse-args
  "Command-line options, kept pure so malformed invocations are testable."
  [args]
  (loop [opts {:out "benchmark.edn"
               :runs default-runs
               :viewport-seconds default-viewport-seconds
               :viewport-mode :hardware}
         [arg value & more :as remaining] args]
    (if-not (seq remaining)
      opts
      (case arg
        "--root" (recur (assoc opts :root value) more)
        "--out" (recur (assoc opts :out value) more)
        "--runs" (recur (assoc opts :runs (parse-long value)) more)
        "--viewport-seconds" (recur (assoc opts :viewport-seconds (parse-long value)) more)
        "--viewport-mode" (recur (assoc opts :viewport-mode (keyword value)) more)
        "--machine" (recur (assoc opts :machine-label value) more)
        "--skip-canary" (recur (assoc opts :skip-canary? true) (next remaining))
        "--skip-viewport" (recur (assoc opts :skip-viewport? true) (next remaining))
        (throw (ex-info (str "unknown benchmark option: " arg) {:option arg}))))))

(defn java->clj
  "Playwright returns java.util collections; convert them recursively."
  [x]
  (cond
    (instance? Map x)  (into {} (map (fn [[k v]] [(keyword (str k)) (java->clj v)])) x)
    (instance? List x) (mapv java->clj x)
    :else              x))

;; --- generic measurement ---------------------------------------------------

(defn- heap-used ^long []
  (.getUsed (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))))

(defn- measured!
  "Run `f`, sampling absolute heap use while it runs."
  [f]
  (System/gc)
  (Thread/sleep 100)
  (let [running (atom true)
        peak    (atom (heap-used))
        sampler (Thread. ^Runnable
                 (fn []
                   (while @running
                     (swap! peak max (heap-used))
                     (Thread/sleep 2)))
                         "shipyard-benchmark-heap")
        started (System/nanoTime)]
    (.setDaemon sampler true)
    (.start sampler)
    (try
      (let [value (f)]
        {:elapsed-ms (/ (- (System/nanoTime) started) 1.0e6)
         :heap-peak-bytes @peak
         :value value})
      (finally
        (reset! running false)
        (.join sampler)))))

(defn- elapsed-ms! [f]
  (let [started (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) started) 1.0e6)))

(defn- temp-dir! [prefix]
  (fs/create-temp-dir {:prefix prefix}))

;; --- library and target selection -----------------------------------------

(defn- source-file [root {:part/keys [id source]}]
  (when source
    (fs/file root id (index/name-of source))))

(defn- triangle-count! [source]
  (let [{:keys [binary? declared]} (canary/header-check (fs/file source))]
    (if binary?
      declared
      (:triangle-count (stl/parse-file! source)))))

(defn- source-info! [root part]
  (let [source (source-file root part)]
    {:part-id (:part/id part)
     :source (str source)
     :source-bytes (fs/size source)
     :triangles (triangle-count! source)}))

(defn- targets! [root parts]
  (let [renderable (filter :part/source parts)
        cruiser    (some #(when (= cruiser-id (:part/id %)) %) renderable)
        sources    (mapv #(source-info! root %) renderable)]
    (when-not cruiser
      (throw (ex-info (str "benchmark cruiser is absent: " cruiser-id)
                      {:part-id cruiser-id})))
    {:cruiser (source-info! root cruiser)
     :heaviest (apply max-key :triangles sources)}))

;; --- scan/index -------------------------------------------------------------

(defn- init-library! [root cache-home]
  (ig/init-key :shipyard.library/index {:root (str root) :cache-home (str cache-home)}))

(defn- measure-starts! [work root runs]
  (loop [i 0, cold [], warm [], parts nil]
    (if (= i runs)
      {:cold-ms (summarize cold)
       :warm-ms (summarize warm)
       :parts parts}
      (let [cache-home (fs/file work "indexes" (str i))
            cold-run   (atom nil)
            cold-ms    (elapsed-ms! #(reset! cold-run (init-library! root cache-home)))
            warm-run   (atom nil)
            warm-ms    (elapsed-ms! #(reset! warm-run (init-library! root cache-home)))]
        (recur (inc i) (conj cold cold-ms) (conj warm warm-ms)
               (index/parts! @warm-run))))))

;; --- preprocessing ---------------------------------------------------------

(defn- cache-map! [cache-home]
  (let [dir (fs/file cache-home "shipyard" "mesh")]
    (fs/create-dirs dir)
    {:dir dir
     :crease-deg 35
     :lod-tiers [1.0 0.25 0.05]
     :cap-bytes 4294967296
     :inflight (atom {}) :files-lock (Object.)}))

(defn- measure-preprocess!
  ([work label target runs]
   (measure-preprocess! work label target runs nil))
  ([work label {:keys [source] :as target} runs shared-cache-home]
   (loop [i 0, samples [], retained nil]
     (if (= i runs)
       {:target target
        :elapsed-ms (summarize (map :elapsed-ms samples))
        :heap-peak-bytes (apply max (map :heap-peak-bytes samples))
        :cache-home (:cache-home retained)
        :mesh-key (get-in retained [:value :mesh-key])
        :tier-count (get-in retained [:value :tiers])}
       (let [cache-home (if (and (zero? i) shared-cache-home)
                          shared-cache-home
                          (fs/file work "preprocess" (name label) (str i)))
             c          (cache-map! cache-home)
             sample     (assoc (measured! #(cache/ensure! c (fs/file source)))
                               :cache-home cache-home)]
         (recur (inc i) (conj samples sample) (or retained sample)))))))

;; --- real HTTP serving -----------------------------------------------------

(defn- system-config [root cache-home]
  {:shipyard.library/index {:root (str root) :cache-home (str cache-home)}
   :shipyard.mesh/cache    {:crease-deg 35 :lod-tiers [1.0 0.25 0.05]
                            :facet-angle-deg 1.0
                            :facet-plane-epsilon-mm 0.01
                            :cap-bytes 4294967296 :cache-home (str cache-home)}
   :shipyard.store/db {:data-home (str cache-home)}
   :shipyard.catalog/db    {:library (ig/ref :shipyard.library/index) :store (ig/ref :shipyard.store/db)}
   :shipyard.http/jobs     {:library (ig/ref :shipyard.library/index)
                            :cache   (ig/ref :shipyard.mesh/cache)}
   :shipyard.http/routes   {:library (ig/ref :shipyard.library/index)
                            :catalog (ig/ref :shipyard.catalog/db)
                            :cache   (ig/ref :shipyard.mesh/cache)
                            :jobs    (ig/ref :shipyard.http/jobs)}
   :shipyard.http/server   {:port 0 :host "127.0.0.1"
                            :handler (ig/ref :shipyard.http/routes)}})

(defn- start-system! [root cache-home]
  (system/start! (system-config root cache-home)))

(defn- server-port ^long [system]
  (let [server ^Server (:shipyard.http/server system)]
    (.getLocalPort ^ServerConnector (first (.getConnectors server)))))

(defn- http-get! [^HttpClient client uri]
  (let [request  (-> (HttpRequest/newBuilder (URI/create uri))
                     (.timeout (Duration/ofSeconds 30))
                     (.GET)
                     (.build))
        response ^HttpResponse (.send client request (HttpResponse$BodyHandlers/ofByteArray))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info (str "benchmark HTTP request returned " (.statusCode response))
                      {:uri uri :status (.statusCode response)})))
    (alength ^bytes (.body response))))

(defn- measure-serve! [system mesh-key runs]
  (let [client (HttpClient/newHttpClient)
        uri    (format "http://127.0.0.1:%d/mesh/%s.0.symesh"
                       (server-port system) mesh-key)]
    ;; Establish the connection and load Jetty's response path before timing.
    (http-get! client uri)
    (let [samples (mapv (fn [_] (elapsed-ms! #(http-get! client uri))) (range runs))]
      {:uri uri
       :bytes (http-get! client uri)
       :elapsed-ms (summarize samples)})))

;; --- actual viewport -------------------------------------------------------

(def viewport-assets
  {"resources/public/js/viewport.js" "npx shadow-cljs compile viewport"
   "resources/public/js/htmx.min.js" "cp node_modules/htmx.org/dist/htmx.min.js resources/public/js/"})

(def viewport-sources ["src/cljs" "src/cljc"])

(defn- newest-viewport-source []
  (->> viewport-sources
       (mapcat #(fs/glob % "**.{cljs,cljc}"))
       (map fs/file)
       (sort-by #(.lastModified ^File %))
       last))

(defn- assert-assets! []
  (doseq [[path command] viewport-assets]
    (when-not (fs/regular-file? path)
      (throw (ex-info (str "missing " path " - run `" command "` first")
                      {:path path}))))
  (let [bundle (fs/file "resources/public/js/viewport.js")
        source (newest-viewport-source)]
    (when (and source (< (.lastModified ^File bundle) (.lastModified ^File source)))
      (throw (ex-info "stale viewport bundle - run `npx shadow-cljs compile viewport`"
                      {:bundle (str bundle) :source (str source)})))
    (when-not (str/includes? (slurp bundle) "__shipyard")
      (throw (ex-info "viewport benchmark needs the dev bundle with test hooks"
                      {:command "npx shadow-cljs compile viewport"})))))

(def chrome-modes
  {:hardware
   {:headless false
    :args ["--use-gl=angle"
           "--use-angle=gl"
           "--ignore-gpu-blocklist"
           "--disable-dev-shm-usage"]}

   ;; A useful conservative comparison and the hermetic CI/E2E renderer, but
   ;; not evidence for §11's hardware-viewport budget.
   :swiftshader
   {:headless true
    :args ["--use-gl=angle"
           "--use-angle=swiftshader"
           "--enable-unsafe-swiftshader"
           "--disable-dev-shm-usage"
           "--no-sandbox"]}})

(defn- wait-until! [f timeout-ms message]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [value (try (f) (catch Exception _ nil))]
        value
        (if (> (System/currentTimeMillis) deadline)
          (throw (ex-info message {:timeout-ms timeout-ms}))
          (do (Thread/sleep 100) (recur)))))))

(defn- viewport-stats! [^Page page]
  (some-> (.evaluate page "() => window.__shipyard ? window.__shipyard.stats() : null")
          (java->clj)))

(defn- measure-viewport! [system {:keys [part-id triangles]} mesh-key seconds mode]
  (assert-assets!)
  (let [{:keys [headless args]} (or (get chrome-modes mode)
                                    (throw (ex-info "unknown viewport mode"
                                                    {:mode mode
                                                     :available (keys chrome-modes)})))
        playwright (Playwright/create)
        browser    (.launch (.chromium playwright)
                            (doto (BrowserType$LaunchOptions.)
                              (.setHeadless headless)
                              (.setArgs args)))
        page       (.newPage browser (doto (Browser$NewPageOptions.)
                                       (.setViewportSize 1280 900)))
        base       (format "http://127.0.0.1:%d" (server-port system))]
    (try
      (.navigate page base)
      (wait-until! #(viewport-stats! page) 30000 "viewport test hook did not start")
      (.evaluate page
                 "payload => document.body.dispatchEvent(new CustomEvent(
                    'shipyard:load-mesh', {detail: {value: payload}}))"
                 (pr-str {:url (format "/mesh/%s.0.symesh" mesh-key)
                          :part-id part-id
                          :frame true}))
      (let [loaded (wait-until!
                    #(let [stats (viewport-stats! page)]
                       (when (some #{part-id} (:parts stats)) stats))
                    60000 "mesh did not reach the viewport")
            renderer (java->clj
                      (.evaluate page
                                 "() => {
                                    const canvas = document.getElementById('viewport');
                                    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
                                    const ext = gl.getExtension('WEBGL_debug_renderer_info');
                                    return {
                                      version: gl.getParameter(gl.VERSION),
                                      renderer: ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)
                                                    : gl.getParameter(gl.RENDERER)
                                    };
                                  }"))
            sample (java->clj
                    (.evaluate page
                               "seconds => new Promise(resolve => {
                                  let frames = 0;
                                  const started = performance.now();
                                  const tick = now => {
                                    frames += 1;
                                    if (now - started >= seconds * 1000) {
                                      resolve({frames: frames,
                                               elapsedMs: now - started,
                                               fps: frames * 1000 / (now - started)});
                                    } else {
                                      requestAnimationFrame(tick);
                                    }
                                  };
                                  requestAnimationFrame(tick);
                                })"
                               (double seconds)))]
        {:part-id part-id
         :mode mode
         :source-triangles triangles
         :rendered-triangles (:triangles loaded)
         :renderer renderer
         :sample sample})
      (finally
        (.close ^Browser browser)
        (.close ^Playwright playwright)))))

;; --- canary ----------------------------------------------------------------

(defn- measure-canary! [root]
  (let [{:keys [elapsed-ms heap-peak-bytes value]}
        (measured!
         #(let [parts (vec (scan/scan! (fs/file root)))]
            (canary/run-canary! {:root root
                                 :parts parts
                                 :crease-deg 35
                                 :lod-tiers [1.0 0.25 0.05]
                                 :thread-count 4})))]
    {:elapsed-ms elapsed-ms
     :heap-peak-bytes heap-peak-bytes
     :parts (:parts value)
     :threads (:threads value)
     :findings (count (:findings value))
     :totals (:totals value)}))

;; --- machine and entry point -----------------------------------------------

(defn- machine [label]
  {:os (str (System/getProperty "os.name") " " (System/getProperty "os.version")
            " " (System/getProperty "os.arch"))
   :java (System/getProperty "java.runtime.version")
   :label label
   :logical-processors (.availableProcessors (Runtime/getRuntime))
   :max-heap-bytes (.maxMemory (Runtime/getRuntime))})

(defn run-benchmark!
  "Run the complete benchmark and return its report."
  [{:keys [root runs viewport-seconds viewport-mode
           skip-canary? skip-viewport? machine-label]}]
  (when-not (and root (fs/directory? root))
    (throw (ex-info "--root must name the model-library directory" {:root root})))
  (when (str/blank? machine-label)
    (throw (ex-info "--machine must label the benchmark hardware" {})))
  (when-not (pos? (long runs))
    (throw (ex-info "--runs must be positive" {:runs runs})))
  (let [work (temp-dir! "shipyard-benchmark-")]
    (try
      (println "measuring cold and warm library starts...")
      (let [{:keys [parts] :as starts} (measure-starts! work root runs)
            targets (targets! root parts)
            source-bytes (reduce + 0 (keep #(some-> (source-file root %) (fs/size)) parts))
            _ (println "preprocessing Cruiser hull...")
            cruiser (measure-preprocess! work :cruiser (:cruiser targets) runs)
            _ (println "preprocessing largest renderable part...")
            heaviest (measure-preprocess! work :heaviest (:heaviest targets) runs
                                          (:cache-home cruiser))
            server (start-system! root (:cache-home cruiser))]
        (try
          (println "measuring cached HTTP response...")
          (let [serve (measure-serve! server (:mesh-key cruiser) runs)
                viewport (when-not skip-viewport?
                           (println "measuring viewport frame rate...")
                           (measure-viewport! server (:heaviest targets)
                                              (:mesh-key heaviest) viewport-seconds
                                              viewport-mode))]
            (println (if skip-canary? "skipping canary" "measuring full-library canary..."))
            {:ran-at (str (Instant/now))
             :machine (machine machine-label)
             :library {:root (str root)
                       :parts (count parts)
                       :renderable-parts (count (filter :part/source parts))
                       :source-bytes source-bytes}
             :starts (dissoc starts :parts)
             :preprocess {:cruiser (dissoc cruiser :cache-home)
                          :heaviest (dissoc heaviest :cache-home)}
             :serve serve
             :viewport viewport
             :canary (when-not skip-canary? (measure-canary! root))})
          (finally
            (system/stop! server))))
      (finally
        (fs/delete-tree work)))))

(defn -main [& args]
  (let [{:keys [out root] :as opts} (parse-args args)
        root (or root (get-in (system/load-config!) [:shipyard.library/index :root]))
        report (run-benchmark! (assoc opts :root root))]
    (system/write-atomically! (fs/file out) (with-out-str (pp/pprint report)))
    (pp/pprint report)
    (println "benchmark report written to" out)))
