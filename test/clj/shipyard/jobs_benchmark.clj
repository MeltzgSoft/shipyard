(ns shipyard.jobs-benchmark
  "On-demand retained-queue and active-thumbnail memory measurements. No app DB
  or cache is opened for writing. Run in a separate JVM with an explicit -Xmx."
  (:require [clojure.edn :as edn]
            [integrant.core :as ig]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.jobs :as mesh-jobs]
            [shipyard.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.loadout.db :as classes]
            [shipyard.loadout.thumbnail :as ships]
            [shipyard.part-browser.thumbnail :as thumbnail]
            [shipyard.part.orientation :as orientation]
            [shipyard.wire :as wire])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files Paths]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- heap! [] (.getUsed (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))))
(defn- collected-heap! []
  (dotimes [_ 3] (System/gc) (Thread/sleep 100))
  (heap!))
(defn- gc-time! []
  (reduce + (map #(.getCollectionTime %) (ManagementFactory/getGarbageCollectorMXBeans))))
(defn- report! [value] (prn value) (flush) value)

(defn- assignments [n]
  (into {} (map (fn [i]
                  (let [hex (Long/toHexString i)]
                    [(str (subs "000000000000000000000000000000000000000000000000000000000000000000000000" (count hex)) hex)
                     "Secondary"])) (range n))))

(defn- queue-task [kind i faces]
  (let [payload (case kind
                  :light {:part (str "Benchmark/Cruiser/" i) :mesh (str i) :pose [0.0 0.0 0.0 1.0]
                          :region {:id i :revision 1} :path (str "/temporary/source/" i "/unsupported.stl")}
                  :regions {:part (str "Benchmark/Cruiser/" i) :faces (assignments faces)})]
    ;; This is the same closure retention mechanism as production jobs; no mesh
    ;; decoding happens in a queued render. Every rich job has independent masks.
    #(count payload)))

(defn- queue-run! [kind faces n]
  (let [pool (ig/init-key :shipyard.jobs/pool {:threads 1 :queue-size (max 1 n)})
        scope (jobs/scope! pool) entered (CountDownLatch. 1) release (CountDownLatch. 1)]
    (try
      (jobs/submit! scope #(do (.countDown entered) (try (.await release) (catch InterruptedException _))))
      (.await entered)
      (let [baseline (collected-heap!)]
        (dotimes [i n] (assert (jobs/submit! scope (queue-task kind i faces))))
        (let [retained (collected-heap!)]
          (report! {:phase :queue :kind kind :faces-per-job faces :queued n
                    :baseline-bytes baseline :retained-bytes retained
                    :delta-bytes (- retained baseline)})))
      (finally (.countDown release) (ig/halt-key! :shipyard.jobs/pool pool)))))

(defn- render! [file painted?]
  (let [mesh (wire/decode (Files/readAllBytes (Paths/get file (make-array String 0))))
        regions (when painted? {:layers ["Primary" "Secondary"]
                                :faces (assignments (quot (count (:indices mesh)) 3))})]
    (alength ^bytes (thumbnail/png! (thumbnail/region-mesh mesh regions) nil))))

(defn- active-run! [file painted? threads n]
  (let [pool (ig/init-key :shipyard.jobs/pool {:threads threads :queue-size n})
        scope (jobs/scope! pool) done (CountDownLatch. n)
        errors (atom []) result-bytes (atom 0)
        baseline (collected-heap!) peak (atom baseline) running (atom true)
        sampler (Thread. ^Runnable #(while @running (swap! peak max (heap!)) (Thread/sleep 5)))
        gc-before (gc-time!) start (System/nanoTime)]
    (.setDaemon sampler true)
    (.start sampler)
    (try
      (dotimes [_ n]
        (assert (jobs/submit! scope
                              #(try (swap! result-bytes + (render! file painted?))
                                    (catch Throwable e (swap! errors conj (str (class e) ": " (ex-message e))))
                                    (finally (.countDown done))))))
      (assert (.await done 10 TimeUnit/MINUTES) "Rendering timed out")
      (report! {:phase :active :source file :painted? painted? :threads threads :jobs n
                :elapsed-ms (/ (- (System/nanoTime) start) 1e6) :gc-ms (- (gc-time!) gc-before)
                :baseline-bytes baseline :peak-bytes @peak :delta-bytes (- @peak baseline)
                :png-bytes @result-bytes :errors @errors})
      (finally (reset! running false) (.join sampler) (ig/halt-key! :shipyard.jobs/pool pool)))))

(defn- thumbnail-queue-run! [n faces]
  (let [started (fixture/start! false fixture/library! fixture/author!
                                {:shipyard.jobs/pool {:threads 1 :queue-size (max 1 n)}})
        sys (:system started) cat (:shipyard.catalog/db sys)
        library (:shipyard.library/index sys) mesh-jobs (:shipyard.http/jobs sys)
        deps {:catalog cat :library library :cache (:shipyard.mesh/cache sys)
              :jobs mesh-jobs :thumbnails (:shipyard.thumbnail/cache sys)}
        entered (CountDownLatch. 1) release (CountDownLatch. 1)
        scope (jobs/scope! (:shipyard.jobs/pool sys))
        ids (vec (repeatedly n random-uuid)) id (:hull fixture/ids)]
    (try
      (mesh-jobs/submit! mesh-jobs id (index/fresh-source-file! library id))
      (loop [attempt 0]
        (when-not (= :ready (:state (mesh-jobs/status mesh-jobs id)))
          (assert (< attempt 1000) "Mesh preparation timed out")
          (Thread/sleep 10) (recur (inc attempt))))
      (catalog/save-regions! cat id {:version 2 :mesh-key (index/mesh-key! library id)
                                     :revision 0 :layer-definitions {} :layers ["Primary" "Secondary"]
                                     :faces (assignments faces)})
      (doseq [id ids]
        (classes/put! (:shipyard.loadout/db sys)
                      {:loadout/id id :loadout/name (str id) :loadout/hull (:hull fixture/ids) :loadout/slots {}} :create))
      (jobs/submit! scope #(do (.countDown entered) (try (.await release) (catch InterruptedException _))))
      (.await entered)
      (let [baseline (collected-heap!)]
        (doseq [id ids]
          (ships/thumbnail! deps {:path-params {:kind "class" :id (str id)}}))
        (let [retained (collected-heap!)]
          (assert (= n (:queued (jobs/progress! (:scope (:thumbnails deps))))) "Thumbnail requests did not enqueue the expected work")
          (report! {:phase :production-queue :queued n :region-faces faces
                    :actual-queue (jobs/progress! (:scope (:thumbnails deps)))
                    :baseline-bytes baseline :retained-bytes retained :delta-bytes (- retained baseline)})))
      (finally (jobs/close! (:scope (:thumbnails deps))) (.countDown release) (fixture/stop! started)))))

(defn- percentile [values fraction]
  (let [values (vec (sort values))]
    (when (seq values) (nth values (min (dec (count values)) (int (* fraction (count values))))))))

(defn- scheduling-run! [{:keys [file threads queue-size jobs] :or {threads 2 queue-size 4096 jobs 300}}]
  (dotimes [_ 5] (render! file false))
  (let [pool (ig/init-key :shipyard.jobs/pool {:threads threads :queue-size queue-size})
        bulk (jobs/scope! pool {:priority :bulk}) interactive (jobs/scope! pool)
        gate (CountDownLatch. 1) done (CountDownLatch. jobs)
        measurements (atom []) accepted (atom 0) rejected (atom 0) probe (promise)
        bean (ManagementFactory/getThreadMXBean) path (Paths/get file (make-array String 0))
        start (System/nanoTime)
        task (fn [submitted pose done!]
               (fn []
                 (.await gate)
                 (try
                   (let [began (System/nanoTime) cpu (.getCurrentThreadCpuTime bean)
                         mesh (wire/decode (Files/readAllBytes path)) read-end (System/nanoTime)
                         png (thumbnail/png! mesh pose) finished (System/nanoTime)]
                     (swap! measurements conj {:wait-ms (/ (- began submitted) 1e6)
                                               :read-ms (/ (- read-end began) 1e6)
                                               :render-ms (/ (- finished read-end) 1e6)
                                               :cpu-ms (/ (- (.getCurrentThreadCpuTime bean) cpu) 1e6)
                                               :png-bytes (alength ^bytes png)}))
                   (finally (done!)))))]
    (try
      (dotimes [i jobs]
        (if (jobs/submit! bulk (task (System/nanoTime) (orientation/from-euler-degrees i 15 0) #(.countDown done)))
          (swap! accepted inc)
          (do (swap! rejected inc) (.countDown done))))
      (let [submitted (System/nanoTime)
            admitted? (jobs/submit! interactive (task submitted nil #(deliver probe (/ (- (System/nanoTime) submitted) 1e6))))]
        (.countDown gate)
        (assert (.await done 5 TimeUnit/MINUTES) "Batch timed out")
        (let [elapsed (/ (- (System/nanoTime) start) 1e6)
              latency (when admitted? (deref probe 30000 :timeout))]
          (report! {:phase :schedule :source file :threads threads :queue-size queue-size :warmup-renders 5
                    :requested jobs :accepted @accepted :rejected @rejected :elapsed-ms elapsed
                    :interactive-admitted? admitted? :interactive-ms latency
                    :throughput-per-second (/ (* 1000 @accepted) elapsed)
                    :wait-p50-ms (percentile (map :wait-ms @measurements) 0.5)
                    :wait-p95-ms (percentile (map :wait-ms @measurements) 0.95)
                    :mean-read-ms (/ (reduce + (map :read-ms @measurements)) (count @measurements))
                    :mean-render-ms (/ (reduce + (map :render-ms @measurements)) (count @measurements))
                    :total-thread-cpu-ms (reduce + (map :cpu-ms @measurements))})))
      (finally (.countDown gate) (ig/halt-key! :shipyard.jobs/pool pool)))))

(defn -main [& [phase options]]
  (let [{:keys [file painted? threads jobs faces counts runs] :or {threads 2 jobs 4 faces 50000 runs 1}} (edn/read-string (or options "{}"))]
    (report! {:phase :environment :jdk (System/getProperty "java.version")
              :processors (.availableProcessors (Runtime/getRuntime)) :max-heap-bytes (.maxMemory (Runtime/getRuntime))})
    (case phase
      "schedule" (scheduling-run! (edn/read-string (or options "{}")))
      "queue" (doseq [n (or counts [0 32 128 512 2048])]
                (queue-run! (if (pos? faces) :regions :light) faces n))
      "thumbnail-queue" (doseq [n (or counts [32 128])] (thumbnail-queue-run! n faces))
      "active" (do (render! file false)
                   (dotimes [_ runs]
                     (doseq [workers (if (sequential? threads) threads [threads])]
                       (active-run! file painted? workers jobs)))))
    (shutdown-agents)))
