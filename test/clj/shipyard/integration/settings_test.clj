(ns shipyard.integration.settings-test
  "Issue #35 acceptance: the library root is a setting.

  No default, set from the UI, persisted, and applied to a running server
  without a restart. Integration rather than unit because every one of those
  claims is about the filesystem: what is on disk, what gets scanned, and what
  is still there after a restart."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.fixtures :as f]
            [shipyard.http.routes :as routes]
            [shipyard.http.settings :as settings]
            [shipyard.library.index :as index]
            [shipyard.system :as system])
  (:import [java.io File]
           [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

(defn- temp-dir ^File [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (random-uuid)))
    (.mkdirs)))

(defn- library-tree
  "A library holding one renderable part, at `part-id`."
  ^File [part-id]
  (let [root (temp-dir "shipyard-settings")
        f    (io/file root part-id "unsupported.stl")]
    (io/make-parents f)
    (with-open [o (io/output-stream f)] (.write o ^bytes (f/->binary-stl (f/cube 2.0))))
    root))

(defn- system
  "A running application that has never been told where its library is - the
  state a fresh install starts in."
  []
  (let [library (ig/init-key :shipyard.library/index
                             {:root nil :cache-home (temp-dir "shipyard-idx")})
        cache   {:dir (temp-dir "shipyard-cache") :crease-deg 35
                 :lod-tiers [1.0 0.25 0.05] :cap-bytes 64000000 :inflight (atom {})}
        catalog (ig/init-key :shipyard.catalog/db {:library library})
        jobs    (ig/init-key :shipyard.http/jobs {:library library :cache cache})]
    {:library library :catalog catalog :cache cache :jobs jobs
     :config-dir (temp-dir "shipyard-cfg")}))

(defn- GET [h path] (h {:request-method :get :uri path}))

(defn- POST
  "A form post, the way the settings form sends one."
  [h path params]
  (let [body (str/join "&" (for [[k v] params]
                             (str (name k) "="
                                  (URLEncoder/encode (str v) StandardCharsets/UTF_8))))]
    (h {:request-method :post
        :uri            path
        :headers        {"content-type" "application/x-www-form-urlencoded"}
        :body           (io/input-stream (.getBytes body StandardCharsets/UTF_8))})))

;; --- a fresh install --------------------------------------------------------

(deftest with-no-root-the-app-starts-and-asks-for-one
  (let [sys (system)
        h   (routes/handler sys)]
    (testing "it starts and serves, rather than failing on a library it has not got"
      (is (= 200 (:status (GET h "/"))))
      (is (= 200 (:status (GET h "/healthz")))))
    (testing "the library panel asks for a folder"
      (let [body (:body (GET h "/library"))]
        (is (str/includes? body "does not know where your models are"))
        (is (str/includes? body "hx-post=\"/settings\""))))
    (testing "and so does the shell, so it can be changed once one is set"
      (is (str/includes? (:body (GET h "/")) "hx-post=\"/settings\"")))))

;; --- setting one ------------------------------------------------------------

(deftest setting-a-root-populates-the-library-without-a-restart
  (let [part-id "Human Navy Fleet Bundle/Cruiser/Hull"
        root    (library-tree part-id)
        sys     (system)
        h       (routes/handler sys)]
    (is (empty? (index/parts (:library sys))))

    (let [r (POST h "/settings" {:root (str root)})]
      (testing "htmx is told to reload: the facets in the shell describe the old library"
        (is (= 204 (:status r)))
        (is (= "true" (get-in r [:headers "HX-Refresh"])))))

    (testing "the same running handler now serves the new library"
      (is (= 1 (count (index/parts (:library sys)))))
      (let [body (:body (GET h "/library"))]
        (is (str/includes? body "1 part"))
        (is (str/includes? body part-id))))

    (testing "the catalog was re-ingested, so the shell's facets follow"
      (is (str/includes? (:body (GET h "/")) "Human Navy Fleet Bundle")))

    (testing "and it survives a restart"
      (let [cfg (system/load-config {:config-dir (str (:config-dir sys)) :env {}})]
        (is (= (str root) (get-in cfg [:shipyard.library/index :root])))))))

(deftest relocating-again-replaces-the-library
  (let [first-root  (library-tree "Bundle A/Cruiser/Hull")
        second-root (library-tree "Bundle B/Escort/Prow")
        sys         (system)
        h           (routes/handler sys)]
    (POST h "/settings" {:root (str first-root)})
    (POST h "/settings" {:root (str second-root)})
    (let [body (:body (GET h "/library"))]
      (is (str/includes? body "Bundle B/Escort/Prow"))
      (is (not (str/includes? body "Bundle A")))
      (is (str/includes? body "1 part")
          "the previous library's parts must not linger in the catalog"))))

;; --- refusing one -----------------------------------------------------------

(deftest a-bad-path-is-refused-and-changes-nothing
  (let [root (library-tree "Bundle/Cruiser/Hull")
        sys  (system)
        h    (routes/handler sys)
        file (io/file root "Bundle/Cruiser/Hull/unsupported.stl")]
    (POST h "/settings" {:root (str root)})

    (doseq [[label path expected]
            [["a folder that is not there" (str root "-nope")   "No such folder"]
             ["a file"                     (str file)           "Not a folder"]
             ["nothing at all"             ""                   "Enter the folder"]]]
      (testing label
        (let [r (POST h "/settings" {:root path})]
          (is (= 422 (:status r)))
          (is (str/includes? (:body r) expected))
          (is (nil? (get-in r [:headers "HX-Refresh"]))))))

    (testing "the library that was working still is"
      (is (= (str root) (index/root (:library sys))))
      (is (str/includes? (:body (GET h "/library")) "Bundle/Cruiser/Hull")))
    (testing "and the saved setting was not overwritten"
      (is (= (str root)
             (get-in (system/load-config {:config-dir (str (:config-dir sys)) :env {}})
                     [:shipyard.library/index :root]))))))

(deftest a-root-that-has-gone-away-reports-itself
  (let [root (library-tree "Bundle/Cruiser/Hull")
        sys  (system)
        h    (routes/handler sys)]
    (POST h "/settings" {:root (str root)})
    ;; The drive is unmounted, or the folder renamed, between runs.
    (index/set-root! (:library sys) (str root "-gone"))
    (let [body (:body (GET h "/library"))]
      (is (str/includes? body "No library at"))
      (is (str/includes? body "hx-post=\"/settings\"")
          "it must offer the way out, not just the diagnosis"))))

;; --- the scan index ---------------------------------------------------------

(deftest the-scan-index-is-not-shared-between-libraries
  (let [part-id "Bundle/Cruiser/Hull"
        a       (library-tree part-id)
        b       (library-tree part-id)          ; same relative path, different files
        cache   (temp-dir "shipyard-idx")
        library (ig/init-key :shipyard.library/index {:root (str a) :cache-home cache})]
    (index/record-mesh-key! library part-id "cafe" 12)
    (is (= "cafe" (index/mesh-key library part-id)))
    (index/set-root! library (str b))
    (is (nil? (index/mesh-key library part-id))
        "a part id is library-relative; serving the stored key would be the wrong mesh")))

;; --- what the validator refuses ---------------------------------------------

(deftest problem-explains-itself
  (let [root (library-tree "Bundle/Cruiser/Hull")]
    (is (nil? (settings/problem (str root))))
    (is (nil? (settings/problem (str "  " root "  ")))
        "a pasted path arrives with whitespace")
    (testing "an empty library is a real answer, not a rejection"
      (is (nil? (settings/problem (str (temp-dir "shipyard-empty"))))))
    (is (some? (settings/problem nil)))
    (is (some? (settings/problem "   ")))
    (is (some? (settings/problem (str root "/no/such/place"))))))
