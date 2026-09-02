(ns shipyard.canary
  "A data-quality probe over the real library (TECHNICAL.md §10.4).

  Deliberately **not** a CI job and not a test level. Fixtures only ever contain
  problems we already know about - the one ASCII STL in this collection was
  found by scanning it, and no fixture suite would have produced it. Run this on
  demand, and after acquiring new bundles.

  **Read-only, absolutely.** It parses, welds and simplifies entirely in memory
  and never calls the mesh cache: filling a 10 GB cache as a side effect of
  auditing would evict everything the user actually looks at. Nothing here opens
  a file for writing except the report, which is written outside the library.

  It is a report, not a gate. Every part is examined even when the one before it
  threw, because the whole point is the list."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [shipyard.library.index :as index]
            [shipyard.library.scan :as scan]
            [shipyard.mesh.lod :as lod]
            [shipyard.mesh.stl :as stl]
            [shipyard.mesh.weld :as weld]
            [shipyard.system :as system])
  (:import [java.io File]
           [java.nio ByteBuffer ByteOrder]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

;; --- geometry checks --------------------------------------------------------

(defn signed-volume
  "Signed volume of a triangle soup, accumulated per face.

  Exact for a closed mesh and near zero for anything flat, which is the case
  worth reporting: a part that is a single plane renders as an invisible sliver
  rather than as an obvious error."
  ^double [^floats positions ^long triangle-count]
  (loop [t 0, acc 0.0]
    (if (>= t triangle-count)
      (/ acc 6.0)
      (let [o (* t 9)
            ax (aget positions o)        ay (aget positions (+ o 1)) az (aget positions (+ o 2))
            bx (aget positions (+ o 3))  by (aget positions (+ o 4)) bz (aget positions (+ o 5))
            cx (aget positions (+ o 6))  cy (aget positions (+ o 7)) cz (aget positions (+ o 8))]
        (recur (inc t)
               (+ acc (- (+ (* ax by cz) (* ay bz cx) (* az bx cy))
                         (+ (* az by cx) (* ay bx cz) (* ax bz cy)))))))))

(defn extents [bbox-min bbox-max]
  (mapv (fn [a b] (Math/abs (- (double b) (double a)))) bbox-min bbox-max))

(def ^:const flat-epsilon
  "Millimetres. Below this an axis has no thickness at print scale, which is the
  scale every one of these files is authored at."
  1.0e-4)

;; --- the 84 + 50n check -----------------------------------------------------

(defn header-check
  "What the binary header claims against what the file actually is.

  A mismatch is not corruption - it is how an ASCII STL presents itself, and
  the parser routes it to the ASCII reader rather than trusting the count. It is
  reported because the count involved is absurd: the one such file here declares
  1,814,065,765 triangles, which a header-trusting parser turns into a 90 GB
  allocation."
  [^File f]
  (let [size (.length f)
        buf  (ByteBuffer/allocate stl/header-bytes)]
    (with-open [in (java.io.FileInputStream. f)]
      (.read in (.array buf)))
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (let [declared (stl/binary-triangle-count buf size)]
      {:size      size
       :declared  declared
       ;; A file too small to hold a header at all is not a failure of the
       ;; 84 + 50n check - there is nothing to check. It is simply corrupt, and
       ;; the parse below reports it as such rather than twice.
       :headerless? (nil? declared)
       :binary?   (boolean (and declared (= size (stl/expected-size declared))))})))

;; --- examining one part -----------------------------------------------------

(defn- finding [part kind detail]
  (merge {:kind kind :part/id (:part/id part)} detail))

(defn examine
  "Every anomaly one part can show, as a vector of findings. Never throws - the
  caller wants the list, not the first entry in it."
  [root {:part/keys [id source] :as part} {:keys [crease-deg lod-tiers]}]
  (if-not source
    [(finding part :no-renderable-variant {:variants (vec (:part/variants part))})]
    (let [f (fs/file root id (index/name-of source))]
      (try
        (let [{:keys [binary? headerless? declared size]} (header-check f)
              header (when-not (or binary? headerless?)
                       [(finding part :header-size-mismatch
                                 {:file (str f) :size size :declared declared})])
              parsed (stl/parse-file f)
              {:keys [^floats positions ^long triangle-count bbox-min bbox-max]} parsed
              ext    (extents bbox-min bbox-max)
              volume (Math/abs (signed-volume positions triangle-count))
              flat   (when (or (some #(< (double %) flat-epsilon) ext)
                               (< volume flat-epsilon))
                       [(finding part :zero-volume {:extents ext :volume volume})])
              ;; weld/weld rather than lod's position-only weld: the ratio worth
              ;; reporting is the crease-split one, since that is what shading
              ;; and buffer size actually cost. It throws at the 2.5 ceiling,
              ;; which the catch below turns into a finding.
              welded (weld/weld parsed {:crease-deg crease-deg :label id})
              ratio  (double (/ (long (:vertex-count welded)) triangle-count))
              high   (when (and (>= triangle-count weld/min-triangles-for-ratio)
                                (> ratio weld/warn-ratio))
                       [(finding part :high-vertex-ratio
                                 {:ratio ratio :triangles triangle-count})])
              tiers  (lod/generate parsed {:crease-deg crease-deg :tiers lod-tiers})
              short? (when (not= (count tiers) (count lod-tiers))
                       [(finding part :missing-tiers
                                 {:got (count tiers) :want (count lod-tiers)})])]
          (vec (concat header flat high short?)))
        (catch Throwable t
          ;; Classified from ex-data rather than by matching the message. Both
          ;; the parser and the weld guard say what went wrong in data; a canary
          ;; that grepped their prose would silently reclassify everything the
          ;; day somebody rewrote a sentence.
          (let [data (ex-data t)
                kind (cond
                       (= 0 (:triangle-count data)) :empty-mesh
                       (:ratio data)                :vertex-ratio-breach
                       :else                        :preprocess-failed)]
            [(finding part kind
                      (cond-> {:file (str f) :message (or (ex-message t) (str (class t)))}
                        (:ratio data) (assoc :ratio (:ratio data)
                                             :triangles (:triangles data))))]))))))

;; --- the run ----------------------------------------------------------------

(def ^:const default-threads
  "Four, not `availableProcessors + 2`. Each worker holds a parsed hull plus its
  welded and simplified derivatives. The largest selected source changes with
  the collection (currently 34.0 MB; an earlier snapshot reached 57.8 MB), and
  a dozen large parts at once blows the 2 GB peak budget (§11). The canary is
  allowed to be slow; it is not allowed to die three hours in."
  4)

(defn- threads [n]
  (max 1 (min (or n default-threads) (.availableProcessors (Runtime/getRuntime)))))

(defn run
  "Examine every part. Returns the report map."
  [{:keys [root parts crease-deg lod-tiers thread-count]
    :or   {crease-deg 35 lod-tiers [1.0 0.25 0.05]}}]
  (let [n     (threads thread-count)
        pool  ^ExecutorService (Executors/newFixedThreadPool n)
        done  (atom 0)
        total (count parts)
        opts  {:crease-deg crease-deg :lod-tiers lod-tiers}]
    (try
      (let [tasks    (mapv (fn [part]
                             ;; A Clojure fn is already a Callable, which is
                             ;; what invokeAll wants.
                             (fn []
                               (let [r (examine root part opts)
                                     d (swap! done inc)]
                                 (when (zero? (mod d 100))
                                   (println (format "  %d/%d parts" d total)))
                                 r)))
                           parts)
            findings (->> (.invokeAll pool tasks)
                          (mapcat #(.get ^java.util.concurrent.Future %))
                          vec)]
        {:root       (str root)
         :ran-at     (str (java.time.Instant/now))
         :parts      total
         :threads    n
         :findings   (vec (sort-by (juxt :kind :part/id) findings))
         :totals     (into (sorted-map) (frequencies (map :kind findings)))})
      (finally
        (.shutdown pool)
        (.awaitTermination pool 1 TimeUnit/SECONDS)))))

;; --- reporting --------------------------------------------------------------

(def kind-labels
  "Every kind the canary can emit, so a clean run prints zeroes rather than an
  empty table - `0 header-size-mismatch` is information, a missing row is not."
  {:no-renderable-variant "parts with no variant Shipyard can open"
   :header-size-mismatch  "files failing the 84 + 50n check"
   :empty-mesh            "meshes with no triangles"
   :zero-volume           "flat or zero-volume meshes"
   :vertex-ratio-breach   "welds at or above the V/T ceiling of 2.5"
   :high-vertex-ratio     "welds above the V/T warning line of 1.25"
   :missing-tiers         "parts that produced fewer LOD tiers than asked for"
   :preprocess-failed     "parts whose preprocessing threw"})

(defn summary-rows [{:keys [totals]}]
  (for [[kind label] (sort-by key kind-labels)]
    {"count" (get totals kind 0) "finding" label "kind" (str kind)}))

(defn print-summary! [{:keys [root parts threads findings] :as report}]
  (println)
  (println "library:" root)
  (println "parts:  " parts (str "(" threads " threads)"))
  (println)
  (pp/print-table ["count" "kind" "finding"] (summary-rows report))
  (println)
  (doseq [[kind group] (sort-by key (group-by :kind findings))
          :when (not= kind :no-renderable-variant)]
    (println (str kind ":"))
    (doseq [f (take 20 group)]
      (println "   " (:part/id f) (if-let [m (:message f)] (str "- " m) "")))
    (when (> (count group) 20)
      (println "    ..." (- (count group) 20) "more - see the EDN report"))
    (println)))

(defn write-report! [file report]
  (system/write-atomically! (fs/file file) (with-out-str (pp/pprint report)))
  file)

;; --- entry point ------------------------------------------------------------

(defn- parse-args [args]
  (reduce (fn [m [k v]]
            (case k
              "--out"     (assoc m :out v)
              "--threads" (assoc m :thread-count (parse-long v))
              "--limit"   (assoc m :limit (parse-long v))
              "--root"    (assoc m :root v)
              m))
          {:out "canary.edn"}
          (partition 2 args)))

(defn -main [& args]
  (let [{:keys [out limit thread-count root]} (parse-args args)
        cfg   (system/load-config)
        root  (or root (get-in cfg [:shipyard.library/index :root]))
        cache (get cfg :shipyard.mesh/cache)
        _     (when-not root
                ;; There is no default library any more (issue #35), and the
                ;; server's setting may never have been made. Say which, rather
                ;; than scanning nil and reporting a library of zero parts.
                (println "No library root. Pass --root, or set one in Shipyard first.")
                (System/exit 2))
        _     (println "scanning" root "...")
        all   (vec (scan/scan (fs/file root)))
        parts (if limit (subvec all 0 (min (long limit) (count all))) all)
        report (run {:root root :parts parts
                     :crease-deg (:crease-deg cache) :lod-tiers (:lod-tiers cache)
                     :thread-count thread-count})]
    (print-summary! report)
    (println "report written to" (str (write-report! out report)))
    ;; Always zero. This is a probe, not a gate (§10.4) - a non-zero exit would
    ;; invite somebody to wire it into CI, where it would fail on data the
    ;; repository does not control.
    (System/exit 0)))
