(ns shipyard.integration.http-test
  "The HTTP surface end to end, against a real library on disk and a real mesh
  pipeline - but no socket. The handler is a function of its dependencies, so
  everything §7 promises can be asserted by calling it (§10.2)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.catalog.db :as db]
            [shipyard.fixtures :as f]
            [shipyard.http.jobs :as jobs]
            [shipyard.http.routes :as routes]
            [shipyard.library.scan :as scan]
            [shipyard.wire :as wire])
  (:import [java.io File]))

;; --- a library on disk ------------------------------------------------------

(def hull-id "Human Navy Fleet Bundle/Cruiser/Cruiser Hull")
(def prow-id "Human Navy Fleet Bundle/Cruiser/Classic Ram Prow")
(def supported-id "Human Navy Fleet Bundle/Cruiser/Supported Only Prow")

(defn- temp-dir ^File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- write-stl! [dir file tris]
  (let [f (io/file dir file)]
    (io/make-parents f)
    (with-open [o (io/output-stream f)] (.write o ^bytes (f/->binary-stl tris)))
    f))

(defn- library-tree ^File []
  (let [root (temp-dir "shipyard-http")]
    (write-stl! (io/file root hull-id) "unsupported.stl" (f/uv-sphere 1.0 8 16))
    (write-stl! (io/file root prow-id) "unsupported.stl" (f/cube 2.0))
    ;; The 73 real folders that ship only a supported mesh, in miniature.
    (write-stl! (io/file root supported-id) "supported.stl" (f/cube 1.0))
    root))

(defn- system
  "The component map the router is handed, built the way integrant builds it."
  [root]
  (let [parts   (scan/scan root)
        library {:root (str root) :available true :parts parts
                 :index (atom {}) :index-file (io/file (temp-dir "shipyard-idx") "index.edn")}
        ;; Built by hand rather than through init-key: that one parks the cache
        ;; under XDG_CACHE_HOME, and a test must not evict the developer's real
        ;; cache to prove a point.
        cache   {:dir (temp-dir "shipyard-http-cache") :crease-deg 35
                 :lod-tiers [1.0 0.25 0.05] :cap-bytes 64000000 :inflight (atom {})}
        catalog {:conn (db/ingest parts (str root)) :root (str root)}
        jobs    (ig/init-key :shipyard.http/jobs {:library library :cache cache})]
    {:library library :catalog catalog :cache cache :jobs jobs}))

(defn- handler [sys] (routes/handler sys))

(defn- GET
  ([h path] (GET h path nil))
  ([h path query]
   (h (cond-> {:request-method :get :uri path}
        query (assoc :query-string query)))))

(defn- triggers
  "The `HX-Trigger` header, decoded: JSON envelope, EDN payloads (§7.1)."
  [response]
  (some-> (get-in response [:headers "HX-Trigger"])
          (json/read-str)
          (update-vals edn/read-string)))

(defn- await-ready
  "Poll `/part/:id` the way the browser does until the mesh URL is issued."
  [h id]
  (let [deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (let [r (GET h (str "/part/" (str/replace id " " "%20")))]
        (cond
          (get (triggers r) "shipyard:load-mesh") r
          (> (System/currentTimeMillis) deadline) (throw (ex-info "part never became ready" {:body (:body r)}))
          :else (do (Thread/sleep 50) (recur)))))))

;; --- the shell --------------------------------------------------------------

(deftest shell-is-a-document-with-the-canvas-island
  (let [h (handler (system (library-tree)))
        {:keys [status headers body]} (GET h "/")]
    (is (= 200 status))
    (is (= "no-store" (get headers "cache-control")))
    (is (str/starts-with? body "<!DOCTYPE html>"))
    (is (re-find #"<canvas[^>]*hx-preserve=\"true\"" body))
    (testing "the filter menus come from the library that was scanned"
      (is (str/includes? body "Human Navy Fleet Bundle"))
      (is (str/includes? body "Cruiser")))))

(deftest healthz-stays-liveness-only
  (let [{:keys [status body]} (GET (handler (system (library-tree))) "/healthz")]
    (is (= 200 status))
    (is (= {"status" "ok"} (json/read-str body)))))

;; --- the library browser ----------------------------------------------------

(deftest library-filters-on-every-parameter
  (let [h (handler (system (library-tree)))]
    (is (= 3 (count (re-seq #"<li class=\"part" (:body (GET h "/library"))))))
    (testing "bundle"
      (is (str/includes? (:body (GET h "/library" "bundle=Human+Navy+Fleet+Bundle")) "Cruiser Hull"))
      (is (str/includes? (:body (GET h "/library" "bundle=Ork+Fleet+Bundle")) "No parts match")))
    (testing "class"
      (is (str/includes? (:body (GET h "/library" "class=Escort")) "No parts match")))
    (testing "role"
      (let [body (:body (GET h "/library" "role=prow"))]
        (is (str/includes? body "Classic Ram Prow"))
        (is (not (str/includes? body "Cruiser Hull")))))
    (testing "free-text name, case-insensitively"
      (is (str/includes? (:body (GET h "/library" "q=ram")) "Classic Ram Prow"))
      (is (not (str/includes? (:body (GET h "/library" "q=ram")) "Cruiser Hull"))))
    (testing "an empty parameter is no filter, which is what \"All\" submits"
      (is (= 3 (count (re-seq #"<li class=\"part" (:body (GET h "/library" "bundle=&class=&role=&q=")))))))
    (testing "fragments are never cached"
      (is (= "no-store" (get-in (GET h "/library") [:headers "cache-control"]))))))

(deftest supported-only-parts-are-greyed-not-hidden
  (let [h    (handler (system (library-tree)))
        body (:body (GET h "/library"))]
    (is (str/includes? body "Supported Only Prow"))
    (is (str/includes? body "part--unrenderable"))
    (is (str/includes? body "supported STL"))))

;; --- part detail ------------------------------------------------------------

(deftest cold-part-returns-immediately-and-triggers-when-ready
  (let [h (handler (system (library-tree)))
        started (System/currentTimeMillis)
        first-r (GET h (str "/part/" (str/replace hull-id " " "%20")))
        elapsed (- (System/currentTimeMillis) started)]
    (testing "the request does not block on the pipeline (§7)"
      (is (= 200 (:status first-r)))
      (is (< elapsed 1000) (str "took " elapsed "ms"))
      (is (str/includes? (:body first-r) "Preparing"))
      (is (str/includes? (:body first-r) "load delay:") "and it polls"))
    (testing "the loading state announces itself on shipyard:status"
      (is (= :preparing (:state (get (triggers first-r) "shipyard:status")))))
    (let [ready (await-ready h hull-id)
          {:strs [shipyard:load-mesh]} (triggers ready)]
      (testing "the mesh URL arrives by HX-Trigger, in the shipyard namespace"
        (is (= hull-id (:part-id shipyard:load-mesh)))
        (is (true? (:frame shipyard:load-mesh)))
        (is (re-matches #"/mesh/[0-9a-f]{64}\.0\.symesh" (:url shipyard:load-mesh))))
      (testing "and the ready fragment stops polling"
        (is (not (str/includes? (:body ready) "load delay:")))))))

(deftest a-known-part-skips-the-job-entirely
  (let [sys (system (library-tree))
        h   (handler sys)]
    (await-ready h hull-id)
    (testing "the mesh key is written back to the scan index (§5.4)"
      (is (re-matches #"[0-9a-f]{64}" (:mesh-key (get @(:index (:library sys)) hull-id)))))
    (testing "so a later request is answered without a poll"
      (let [r (GET h (str "/part/" (str/replace hull-id " " "%20")))]
        (is (get (triggers r) "shipyard:load-mesh"))
        (is (not (str/includes? (:body r) "load delay:")))))))

(deftest supported-only-part-detail-explains-and-clears-the-scene
  (let [h (handler (system (library-tree)))
        r (GET h (str "/part/" (str/replace supported-id " " "%20")))]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "supported STL"))
    (is (contains? (triggers r) "shipyard:clear"))
    (is (not (str/includes? (:body r) "load delay:")) "nothing to wait for")))

(deftest unknown-part-is-a-404-fragment
  (let [h (handler (system (library-tree)))
        r (GET h "/part/Nope/Not/Here")]
    (is (= 404 (:status r)))
    (is (contains? (triggers r) "shipyard:clear"))))

(deftest ids-with-spaces-survive-the-round-trip
  (testing "most of this library has spaces in its folder names"
    (let [h (handler (system (library-tree)))
          r (GET h "/part/Human%20Navy%20Fleet%20Bundle/Cruiser/Classic%20Ram%20Prow")]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "Classic Ram Prow")))))

;; --- meshes -----------------------------------------------------------------

(deftest mesh-is-binary-and-immutable
  (let [h    (handler (system (library-tree)))
        url  (:url (get (triggers (await-ready h hull-id)) "shipyard:load-mesh"))
        {:keys [status headers body]} (GET h url)]
    (is (= 200 status))
    (is (= "public, max-age=31536000, immutable" (get headers "cache-control")))
    (is (= "application/octet-stream" (get headers "content-type")))
    (is (pos? (parse-long (get headers "content-length"))))
    (testing "and it really is a .symesh"
      (let [bytes (with-open [in  (io/input-stream body)
                              out (java.io.ByteArrayOutputStream.)]
                    (io/copy in out)
                    (.toByteArray out))
            mesh  (wire/decode bytes)]
        (is (= (mapv byte wire/magic-bytes) (vec (take 8 bytes))))
        (is (pos? (:vertex-count mesh)))
        (is (pos? (:index-count mesh)))
        (is (not= (:bbox-min mesh) (:bbox-max mesh)) "the header frames the camera")))))

(deftest mesh-route-refuses-anything-but-a-digest
  (let [h (handler (system (library-tree)))]
    (doseq [path ["/mesh/nope.0.symesh"
                  "/mesh/....%2F....%2Fetc%2Fpasswd.0.symesh"
                  (str "/mesh/" (apply str (repeat 64 "a")) ".0.symesh")]]
      (is (= 404 (:status (GET h path))) path))))

;; --- failures ---------------------------------------------------------------

(deftest a-broken-stl-reports-and-offers-a-retry
  (let [root (library-tree)
        _    (spit (io/file root prow-id "unsupported.stl") "not an stl at all")
        h    (handler (system root))
        url  (str "/part/" (str/replace prow-id " " "%20"))
        deadline (+ (System/currentTimeMillis) 30000)
        failed   (loop []
                   (let [r (GET h url)]
                     (cond
                       (str/includes? (:body r) "Try again") r
                       (> (System/currentTimeMillis) deadline) (throw (ex-info "never failed" {}))
                       :else (do (Thread/sleep 50) (recur)))))]
    (testing "the failure is shown, not swallowed"
      (is (= :failed (:state (get (triggers failed) "shipyard:status"))))
      (is (not (str/includes? (:body failed) "load delay:"))
          "a failed job must not be retried silently on the next poll"))))

(deftest jobs-are-idempotent-under-concurrent-requests
  (let [sys (system (library-tree))
        h   (handler sys)
        url (str "/part/" (str/replace hull-id " " "%20"))
        rs  (->> (repeatedly 8 #(future (GET h url))) doall (mapv deref))]
    (is (every? #(= 200 (:status %)) rs))
    (await-ready h hull-id)
    (testing "one job ran, not eight"
      (is (= :ready (:state (jobs/status (:jobs sys) hull-id)))))))

(deftest an-unwritable-index-does-not-fail-a-good-part
  (testing "recording the mesh key is an optimisation. Windows CI caught this:
            a transient AccessDeniedException on the index write reported a part
            that had preprocessed perfectly as failed."
    (let [sys (system (library-tree))
          ;; A directory where a file should be. Every write to it fails, on
          ;; every platform, without needing a scanner to hold a handle.
          sys (assoc-in sys [:library :index-file] (temp-dir "shipyard-not-a-file"))
          h     (handler sys)
          ready (await-ready h hull-id)]
      (is (get (triggers ready) "shipyard:load-mesh"))
      (is (= :ready (:state (jobs/status (:jobs sys) hull-id)))))))

(deftest missing-library-root-says-so
  (let [sys (assoc-in (system (library-tree)) [:library :available] false)
        h   (handler sys)]
    (is (str/includes? (:body (GET h "/library")) "No library at"))))
