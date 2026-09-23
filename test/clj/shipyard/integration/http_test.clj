(ns shipyard.integration.http-test
  "The HTTP surface end to end, against a real library on disk and a real mesh
  pipeline - but no socket. The handler is a function of its dependencies, so
  everything §7 promises can be asserted by calling it (§10.2)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [shipyard.fixtures :as f]
            [shipyard.http.jobs :as jobs]
            [shipyard.http.routes :as routes]
            [shipyard.catalog.db :as catalog-db]
            [shipyard.mesh.cache :as cache]
            [shipyard.library.index :as index]
            [shipyard.catalog.sidecar :as sidecar]
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
  "The component map the router is handed, built the way integrant builds it.

  The library and catalog go through their real `init-key`s, pointed at a temp
  cache home: they own a mutable state atom now that the root is a setting
  (issue #35), and a hand-built stand-in would be free to drift out of the
  shape the handlers read."
  [root]
  (let [library (ig/init-key :shipyard.library/index
                             {:root (str root) :cache-home (temp-dir "shipyard-idx")})
        ;; Built by hand rather than through init-key: that one parks the cache
        ;; under XDG_CACHE_HOME, and a test must not evict the developer's real
        ;; cache to prove a point.
        cache   {:dir (temp-dir "shipyard-http-cache") :crease-deg 35
                 :lod-tiers [1.0 0.25 0.05]
                 :facet-angle-deg 1.0
                 :facet-plane-epsilon-mm 0.01
                 :cap-bytes 64000000 :inflight (atom {}) :files-lock (Object.)}
        catalog (ig/init-key :shipyard.catalog/db {:library library})
        jobs    (ig/init-key :shipyard.http/jobs {:library library :cache cache})]
    {:library library :catalog catalog :cache cache :jobs jobs
     ;; Never the developer's real config dir: relocating writes a file.
     :config-dir (temp-dir "shipyard-cfg")}))

(defn- handler [sys] (routes/handler sys))

(defn- GET
  ([h path] (GET h path nil))
  ([h path query]
   (h (cond-> (mock/request :get path)
        query (mock/query-string query)))))

(defn- POST
  "A form post, the way htmx sends one."
  [h path params]
  (h (mock/request :post path params)))

(defn- triggers
  "Decode both immediate and after-swap htmx events."
  [response]
  (into {} (mapcat (fn [header]
                     (some-> (get-in response [:headers header])
                             json/read-str
                             (update-vals edn/read-string))))
        ["HX-Trigger" "HX-Trigger-After-Swap"]))

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

(defn- authoring-mesh
  [triangles]
  (let [positions (float-array (mapcat identity (apply concat triangles)))
        indices (int-array (range (* 3 (count triangles))))]
    {:positions positions
     :indices indices
     :vertex-count (* 3 (count triangles))
     :bbox-min [0.0 0.0 0.0]
     :bbox-max [4.0 2.0 0.0]}))

(def ^:private facet-mesh
  (authoring-mesh
   [[[0 0 0] [4 0 0] [0 2 0]]
    [[4 0 0] [4 2 0] [0 2 0]]]))

(def ^:private degenerate-mesh
  (authoring-mesh [[[0 0 0] [1 0 0] [1 0 0]]]))

(defn- write-symesh! [cache mesh-key mesh]
  (let [f (cache/tier-file cache mesh-key 0)]
    (fs/create-dirs (fs/parent f))
    (io/copy (wire/encode mesh) (fs/file f))
    f))

(defn- seed-authoring-cache!
  ([sys] (seed-authoring-cache! sys (apply str (repeat 64 "1")) facet-mesh))
  ([sys mesh-key mesh]
   (write-symesh! (:cache sys) mesh-key mesh)
   (swap! (:state (:library sys)) update-in [:entries hull-id] assoc :mesh-key mesh-key)
   mesh-key))

(defn- facet-post
  ([h part-id mesh-key triangle-index]
   (facet-post h part-id mesh-key triangle-index nil))
  ([h part-id mesh-key triangle-index params]
   (POST h "/facet" (merge {:part-id part-id :mesh-key mesh-key :triangle-index triangle-index}
                           params))))

(defn- mount-post [h params]
  (POST h "/mounts" params))

(defn- mount-edit [h params]
  (POST h "/mounts/edit" params))

(defn- mount-delete [h params]
  (POST h "/mounts/delete" params))

(defn- part-role-post [h params]
  (POST h "/parts/role" params))

(defn- part-orientation-post [h params]
  (POST h "/parts/orientation" params))

;; --- the shell --------------------------------------------------------------

(deftest shell-is-a-document-with-the-canvas-island
  (let [h (handler (system (library-tree)))
        {:keys [status headers body]} (GET h "/")]
    (is (= 200 status))
    (is (= "no-store" (get headers "cache-control")))
    (is (str/starts-with? body "<!DOCTYPE html>"))
    (is (re-find #"<canvas[^>]*hx-preserve=\"true\"" body))
    (testing "static assets revalidate after an editor rebuild"
      ;; The viewport bundle is generated and deliberately ignored by git, so
      ;; a clean CI checkout does not contain it until the frontend build.  The
      ;; static handler applies this policy uniformly; app.css is a committed
      ;; representative that makes the integration assertion independent of
      ;; that separate build step.
      (let [{:keys [status headers]} (GET h "/app.css")]
        (is (= 200 status))
        (is (= "no-cache" (get headers "cache-control")))))
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
      (testing "the mesh URL arrives after the swap, in the shipyard namespace"
        (is (get-in ready [:headers "HX-Trigger-After-Swap"]))
        (is (not (contains? shipyard:load-mesh :regions)))
        (is (= hull-id (:part-id shipyard:load-mesh)))
        (is (true? (:frame shipyard:load-mesh)))
        (is (= [0.0 0.0 0.0 1.0] (:orientation shipyard:load-mesh)))
        (is (re-matches #"/mesh/[0-9a-f]{64}\.0\.symesh" (:url shipyard:load-mesh))))
      (testing "and the ready fragment stops polling"
        (is (not (str/includes? (:body ready) "load delay:")))))))

(deftest configured-mounts-are-sent-to-the-viewer
  (let [root (library-tree)
        mount {:mount/id :weapon-1
               :mount/kind :socket
               :mount/accepts #{:weapon}
               :mount/capacity 2
               :mount/pos [0.0 0.0 1.0]
               :mount/axis [0.0 0.0 1.0]
               :mount/roll [1.0 0.0 0.0]
               :mount/origin :picked}
        _ (sidecar/write-sidecar! root hull-id {:mounts [mount] :part/role :hull})
        h (handler (system root))
        ready (await-ready h hull-id)
        load-mesh (get (triggers ready) "shipyard:load-mesh")]
    (is (= [(update mount :mount/accepts vec)] (:mounts load-mesh)))
    (is (str/includes? (:body ready) "Interface colors"))
    (is (str/includes? (:body ready) "weapon socket"))))

(deftest a-known-part-skips-the-job-entirely
  (let [sys (system (library-tree))
        h   (handler sys)]
    (await-ready h hull-id)
    (testing "the mesh key is written back to the scan index (§5.4)"
      (is (re-matches #"[0-9a-f]{64}" (index/mesh-key! (:library sys) hull-id))))
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
    (testing "Malli rejects malformed path parameters at the Ring boundary"
      (doseq [path ["/mesh/nope.0.symesh"
                    "/mesh/....%2F....%2Fetc%2Fpasswd.0.symesh"]]
        (is (= 400 (:status (GET h path))) path)))
    (testing "a valid but absent digest reaches the handler"
      (let [path (str "/mesh/" (apply str (repeat 64 "a")) ".0.symesh")]
        (is (= 404 (:status (GET h path))) path)))))

;; --- facet preview ----------------------------------------------------------

(deftest facet-preview-posts-a-triangle-selection
  (let [sys (system (library-tree))
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        r (facet-post h hull-id mesh-key 0)
        preview (get (triggers r) "shipyard:facet-preview")]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "Face selected."))
    (is (= hull-id (:part-id preview)))
    (is (= mesh-key (:mesh-key preview)))
    (is (= 0 (:triangle-index preview)))
    (is (= [0 1] (:facet-indices preview)))
    (is (= [2.0 1.0 0.0] (get-in preview [:frame :mount/pos])))
    (is (= [0.0 0.0 1.0] (get-in preview [:frame :mount/axis])))
    (is (= [1.0 0.0 0.0] (get-in preview [:frame :mount/roll])))
    (is (false? (:roll-ambiguous? preview)))
    (is (= :part-orientation (:roll-source preview)))
    ;; The flat fixture is deliberately role-hinted as a hull. Geometry takes
    ;; precedence only for the initial, still-editable kind form default.
    (is (re-find #"<option selected=\"selected\" value=\"plug\">plug</option>" (:body r)))
    (is (str/includes? (:body r) "Geometry suggests plug. You can change this."))))

(deftest mount-wizard-saves-replaces-and-deletes
  (let [root (library-tree)
        sys (system root)
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        preview (get (triggers (facet-post h hull-id mesh-key 0)) "shipyard:facet-preview")
        save-params {:part-id hull-id
                     :mount-id "port-1"
                     :kind "socket"
                     :accepts "weapon"
                     :capacity "2"
                     :frame (pr-str (:frame preview))
                     :mesh-key mesh-key
                     :facet-indices (pr-str (:facet-indices preview))
                     :roll-deg "0"
                     :action "create"}
        saved (mount-post h save-params)]
    (is (= 200 (:status saved)))
    (is (contains? (triggers saved) "shipyard:clear-preview"))
    (is (= :exit (:state (get (triggers saved) "shipyard:authoring"))))
    (is (= {:part-id hull-id
            :mesh-key mesh-key
            :mounts [{:mount/id :port-1
                      :mount/kind :socket
                      :mount/accepts [:weapon]
                      :mount/capacity 2
                      :mount/split {:direction :vertical :bounds [[-2.0 -1.0] [2.0 1.0]]}
                      :mount/pos [2.0 1.0 0.0]
                      :mount/axis [0.0 0.0 1.0]
                      :mount/roll [1.0 0.0 0.0]
                      :mount/facet {:mesh-key mesh-key :indices [0 1]}
                      :mount/origin :picked}]}
           (get (triggers saved) "shipyard:interfaces")))
    (is (str/includes? (:body saved) "port-1"))
    (is (str/includes? (:body saved) "x2"))
    (is (str/includes? (:body saved) "Interface colors"))
    (let [sidecar (sidecar/read-sidecar! root hull-id)
          mount (first (:mounts sidecar))]
      (is (nil? (:part/role sidecar)))
      (is (= :port-1 (:mount/id mount)))
      (is (= :socket (:mount/kind mount)))
      (is (= #{:weapon} (:mount/accepts mount)))
      (is (= 2 (:mount/capacity mount)))
      (is (= [2.0 1.0 0.0] (:mount/pos mount)))
      (is (= {:mesh-key mesh-key :indices [0 1]} (:mount/facet mount)))
      (is (nil? (:facet-indices mount))))
    (testing "duplicate ids require deliberate replacement"
      (let [duplicate (mount-post h save-params)]
        (is (= 200 (:status duplicate)))
        (is (str/includes? (:body duplicate) "already exists"))
        (is (str/includes? (:body duplicate) "id=\"mount-authoring\""))
        (is (str/includes? (:body duplicate) "Replace"))
        (is (str/includes? (:body duplicate) "Dismiss"))
        (is (str/includes? (:body duplicate) "value=\"port-1\""))
        (is (= {:state :enter :part-id hull-id :mesh-key mesh-key}
               (get (triggers duplicate) "shipyard:authoring")))))
    (testing "a forged multi-role request is rejected even though the browser uses radios"
      (let [rejected (mount-post h (assoc save-params :accepts ["weapon" "turret"]))]
        (is (= 200 (:status rejected)))
        (is (str/includes? (:body rejected) "Choose one role"))
        (is (= #{:weapon}
               (:mount/accepts (first (:mounts (sidecar/read-sidecar! root hull-id))))))))

    (testing "facet indices must be bounded and refer to the current mesh"
      (is (= 400 (:status (mount-post h (assoc save-params :facet-indices "[999999999999999999999]")))))
      (is (= 422 (:status (mount-post h (assoc save-params :facet-indices "[2]"))))))
    (testing "replace updates the durable mount instead of accumulating"
      (let [replaced (mount-post h (assoc save-params :accepts "prow" :action "replace"))
            mounts (:mounts (sidecar/read-sidecar! root hull-id))]
        (is (= 200 (:status replaced)))
        (is (= 1 (count mounts)))
        (is (= #{:prow} (:mount/accepts (first mounts))))))
    (testing "delete removes the mount deliberately"
      (let [deleted (mount-delete h {:part-id hull-id :mount-id "port-1"})]
        (is (= 200 (:status deleted)))
        (is (= {:part-id hull-id :mesh-key mesh-key :mounts []}
               (get (triggers deleted) "shipyard:interfaces")))
        (is (empty? (:mounts (sidecar/read-sidecar! root hull-id))))
        (is (not (str/includes? (:body deleted) "port-1")))))))

(deftest hull-acceptance-profile-allows-turrets-or-antennae
  (let [root (library-tree)
        sys (system root)
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        preview-response (facet-post h hull-id mesh-key 0)
        frame (:frame (get (triggers preview-response) "shipyard:facet-preview"))
        saved (mount-post h {:part-id hull-id
                             :mount-id "top-seat"
                             :kind "socket"
                             :accepts "turret-or-antenna"
                             :capacity "1"
                             :frame (pr-str frame)
                             :roll-deg "0"
                             :action "create"})]
    (is (str/includes? (:body preview-response) "Turret or antenna hardpoint"))
    (is (= 200 (:status saved)))
    (is (= #{:turret :antenna}
           (:mount/accepts (first (:mounts (sidecar/read-sidecar! root hull-id))))))))

(deftest part-detail-backfills-legacy-mount-facets-on-the-server
  (let [root (library-tree)
        mount {:mount/id :port-1
               :mount/kind :socket
               :mount/accepts #{:weapon}
               :mount/capacity 1
               :mount/pos [2.0 1.0 0.0]
               :mount/axis [0.0 0.0 1.0]
               :mount/roll [1.0 0.0 0.0]
               :mount/origin :picked}
        _ (sidecar/write-sidecar! root hull-id {:mounts [mount]})
        sys (system root)
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        first-response (GET h (str "/part/" (str/replace hull-id " " "%20")))
        ready (await-ready h hull-id)
        mounts (:mounts (get (triggers ready) "shipyard:load-mesh"))]
    (testing "the request polls while a bounded server job recovers faces"
      (is (str/includes? (:body first-response) "Preparing this part"))
      (is (nil? (get (triggers first-response) "shipyard:load-mesh"))))
    (testing "the completed response and durable sidecar carry direct indices"
      (is (= 1 (count (:part/mounts (catalog-db/part (catalog-db/snapshot! (:catalog sys)) hull-id)))))
      (is (= [{:mount/id :port-1
               :mount/pos [2.0 1.0 0.0]
               :mount/axis [0.0 0.0 1.0]
               :mount/facet {:mesh-key mesh-key :indices [0 1]}}]
             (mapv #(select-keys % [:mount/id :mount/pos :mount/axis :mount/facet]) mounts)))
      (is (= [{:mesh-key mesh-key :indices [0 1]}]
             (mapv :mount/facet mounts)))
      (is (= {:mesh-key mesh-key :indices [0 1]}
             (:mount/facet (first (:mounts (sidecar/read-sidecar! root hull-id)))))))))

(deftest mount-wizard-mirrors-and-repeats
  (let [root (library-tree)
        sys (system root)
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        preview (get (triggers (facet-post h hull-id mesh-key 0)) "shipyard:facet-preview")
        saved (mount-post h {:part-id hull-id
                             :mount-id "port-1"
                             :kind "socket"
                             :accepts "turret"
                             :capacity "2"
                             :frame (pr-str (:frame preview))
                             :roll-deg "0"
                             :mirror "true"
                             :mirror-plane "x"
                             :mirror-offset "0"
                             :mirror-id "starboard-1"
                             :repeat "true"
                             :action "create"})
        events (triggers saved)
        mounts (:mounts (sidecar/read-sidecar! root hull-id))
        by-id (into {} (map (juxt :mount/id identity)) mounts)]
    (is (= 200 (:status saved)))
    (is (str/includes? (:body saved) "port-1"))
    (is (str/includes? (:body saved) "starboard-1"))
    (is (= :enter (:state (get events "shipyard:authoring"))))
    (is (= {:mount-id "port-2"
            :kind "socket"
            :accepts #{:turret}}
           (get events "shipyard:mount-repeat")))
    (is (= :picked (get-in by-id [:port-1 :mount/origin])))
    (is (= :mirrored (get-in by-id [:starboard-1 :mount/origin])))
    (is (= :starboard-1 (get-in by-id [:port-1 :mount/mirror-id])))
    (is (= :port-1 (get-in by-id [:starboard-1 :mount/mirror-id])))
    (is (= 2 (get-in by-id [:port-1 :mount/capacity])))
    (is (= 2 (get-in by-id [:starboard-1 :mount/capacity])))
    (is (= [-2.0 1.0 0.0] (get-in by-id [:starboard-1 :mount/pos])))
    (is (nil? (get-in by-id [:starboard-1 :facet-indices])))
    (testing "the next preview is prefilled from the repeated classification"
      (let [repeated (:body (facet-post h hull-id mesh-key 0
                                        {"mount-id" "port-2"
                                         "kind" "socket"
                                         "accepts" "turret"}))]
        (is (str/includes? repeated "value=\"port-2\""))
        (is (re-find #"name=\"capacity\"[^>]+value=\"1\"" repeated))
        (is (re-find #"selected=\"selected\"[^>]+value=\"turret\"" repeated))))))

(deftest mount-wizard-edits-existing-mounts
  (let [root (library-tree)
        sys (system root)
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        preview (get (triggers (facet-post h hull-id mesh-key 0)) "shipyard:facet-preview")
        _saved (mount-post h {:part-id hull-id
                              :mount-id "port-1"
                              :kind "socket"
                              :accepts "weapon"
                              :capacity "2"
                              :frame (pr-str (:frame preview))
                              :roll-deg "0"
                              :action "create"})
        edit (mount-edit h {:part-id hull-id :mount-id "port-1"})
        edit-events (triggers edit)
        edit-preview (get edit-events "shipyard:facet-preview")]
    (is (= 200 (:status edit)))
    (is (str/includes? (:body edit) "Save changes"))
    (is (str/includes? (:body edit) "name=\"original-mount-id\""))
    (is (str/includes? (:body edit) "value=\"port-1\""))
    (is (str/includes? (:body edit) "Normal (+Z)"))
    (is (str/includes? (:body edit) "Twist"))
    (is (re-find #"selected=\"selected\"[^>]+value=\"weapon\"" (:body edit)))
    (is (= {:state :enter :part-id hull-id :mesh-key mesh-key}
           (get edit-events "shipyard:authoring")))
    (is (= (:frame preview) (:frame edit-preview)))
    (is (= :saved-mount (:roll-source edit-preview)))
    (testing "picking another face preserves edit mode and non-kind values"
      (let [repicked (facet-post h hull-id mesh-key 0
                                 {"original-mount-id" "port-1"
                                  "mount-id" "port-1"
                                  "kind" "socket"
                                  "accepts" "weapon"
                                  "capacity" "3"
                                  "twist-deg" "90"})]
        (is (= 200 (:status repicked)))
        (is (str/includes? (:body repicked) "Save changes"))
        (is (str/includes? (:body repicked) "name=\"original-mount-id\""))
        (is (re-find #"<option selected=\"selected\" value=\"plug\">plug</option>" (:body repicked)))
        (is (re-find #"selected=\"selected\"[^>]+value=\"weapon\"" (:body repicked)))
        (is (re-find #"name=\"capacity\"[^>]+value=\"3\"" (:body repicked)))))
    (testing "update changes the existing mount instead of leaving the old id behind"
      (let [updated (mount-post h {:part-id hull-id
                                   :original-mount-id "port-1"
                                   :mount-id "prow-socket"
                                   :kind "socket"
                                   :accepts "prow"
                                   :capacity "1"
                                   :frame (pr-str (:frame edit-preview))
                                   :twist-deg "90"
                                   :action "update"})
            mounts (:mounts (sidecar/read-sidecar! root hull-id))
            mount (first mounts)]
        (is (= 200 (:status updated)))
        (is (= 1 (count mounts)))
        (is (= :prow-socket (:mount/id mount)))
        (is (= #{:prow} (:mount/accepts mount)))
        (is (< (Math/abs (- 1.0 (double (second (:mount/roll mount)))))
               1.0e-6))))))

(deftest part-role-is-edited-outside-the-mount-wizard
  (let [root (library-tree)
        sys (system root)
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        preview (get (triggers (facet-post h hull-id mesh-key 0)) "shipyard:facet-preview")
        saved (mount-post h {:part-id hull-id
                             :mount-id "port-1"
                             :kind "socket"
                             :accepts "weapon"
                             :capacity "2"
                             :part-role "weapon"
                             :frame (pr-str (:frame preview))
                             :action "create"})]
    (testing "mount saves ignore any stray part-role field"
      (is (= 200 (:status saved)))
      (is (nil? (:part/role (sidecar/read-sidecar! root hull-id)))))
    (testing "the standalone metadata form persists the role override"
      (let [role-saved (part-role-post h {:part-id hull-id :part-role "hull"})
            sidecar (sidecar/read-sidecar! root hull-id)]
        (is (= 200 (:status role-saved)))
        (is (= :hull (:part/role sidecar)))
        (is (str/includes? (:body role-saved) "Part metadata"))
        (is (str/includes? (:body role-saved) "Manual"))))))

(deftest part-orientation-is-edited-outside-the-mount-wizard
  (let [root (library-tree)
        sys (system root)
        h (handler sys)
        mesh-key (seed-authoring-cache! sys)
        saved (part-orientation-post h {:part-id hull-id
                                        :part-yaw-deg "90"
                                        :part-pitch-deg "0"
                                        :part-roll-deg "0"
                                        :action "save"})
        part-orientation (:part/orientation (sidecar/read-sidecar! root hull-id))]
    (is (= 200 (:status saved)))
    (is (= part-orientation
           (:orientation (get (triggers saved) "shipyard:part-orientation"))))
    (is (true? (:saved? (get (triggers saved) "shipyard:part-orientation"))))
    (is (re-find #"name=\"part-yaw-deg\"[^>]+value=\"90.0\"" (:body saved)))
    (testing "newly selected mount frames follow canonical part-up"
      (let [preview (get (triggers (facet-post h hull-id mesh-key 0))
                         "shipyard:facet-preview")]
        (is (= :part-orientation (:roll-source preview)))
        (is (false? (:roll-ambiguous? preview)))))
    (testing "reset persists identity and updates the live viewer"
      (let [reset-response (part-orientation-post h {:part-id hull-id :action "reset"})]
        (is (= [0.0 0.0 0.0 1.0]
               (:part/orientation (sidecar/read-sidecar! root hull-id))))
        (is (= [0.0 0.0 0.0 1.0]
               (:orientation
                (get (triggers reset-response) "shipyard:part-orientation"))))
        (is (true? (:saved?
                    (get (triggers reset-response) "shipyard:part-orientation"))))))
    (testing "invalid values keep the loaded detail and restore the saved orientation"
      (let [invalid (part-orientation-post h {:part-id hull-id
                                              :part-yaw-deg "NaN"
                                              :part-pitch-deg "0"
                                              :part-roll-deg "0"
                                              :action "save"})]
        (is (= 200 (:status invalid)))
        (is (str/includes? (:body invalid) "finite angles"))
        (is (str/includes? (:body invalid) "id=\"mount-authoring\""))))))

(deftest mount-wizard-reports-validation-errors
  (let [sys (system (library-tree))
        h (handler sys)]
    (testing "malformed frames"
      (let [r (mount-post h {:part-id hull-id
                             :mount-id "port-1"
                             :kind "socket"
                             :accepts "weapon"
                             :frame "{:not :a-frame}"
                             :action "create"})]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "valid frame"))))
    (testing "bad capacity"
      (let [r (mount-post h {:part-id hull-id
                             :mount-id "port-1"
                             :kind "socket"
                             :accepts "weapon"
                             :capacity "0"
                             :frame (pr-str {:mount/pos [0 0 0]
                                             :mount/axis [0 0 1]
                                             :mount/roll [1 0 0]})
                             :action "create"})]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "Capacity"))))
    (testing "bad mount ids"
      (let [r (mount-post h {:part-id hull-id
                             :mount-id "1 bad"
                             :kind "socket"
                             :accepts "weapon"
                             :frame (pr-str {:mount/pos [0 0 0]
                                             :mount/axis [0 0 1]
                                             :mount/roll [1 0 0]})
                             :action "create"})]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "Mount ids"))))))

(deftest facet-preview-reports-documented-errors
  (testing "missing or malformed fields"
    (let [r (POST (handler (system (library-tree))) "/facet" {})]
      (is (= 400 (:status r)))
      (is (= :reitit.coercion/request-coercion (:type (:body r))))))

  (testing "non-decimal triangle index"
    (let [r (facet-post (handler (system (library-tree))) hull-id (apply str (repeat 64 "1")) "1e3")]
      (is (= 400 (:status r)))
      (is (= :reitit.coercion/request-coercion (:type (:body r))))))

  (testing "part absent from the current catalog"
    (let [r (facet-post (handler (system (library-tree))) "No/Such/Part" (apply str (repeat 64 "1")) 0)]
      (is (= 404 (:status r)))
      (is (= :part-not-found (:code (get (triggers r) "shipyard:facet-error"))))))

  (testing "no current mesh key"
    (let [r (facet-post (handler (system (library-tree))) hull-id (apply str (repeat 64 "1")) 0)]
      (is (= 409 (:status r)))
      (is (= :mesh-not-ready (:code (get (triggers r) "shipyard:facet-error"))))))

  (testing "key mismatch"
    (let [sys (system (library-tree))
          h (handler sys)
          _ (seed-authoring-cache! sys (apply str (repeat 64 "1")) facet-mesh)
          r (facet-post h hull-id (apply str (repeat 64 "2")) 0)]
      (is (= 409 (:status r)))
      (is (= :stale-mesh (:code (get (triggers r) "shipyard:facet-error"))))))

  (testing "source mtime or size changed"
    (let [root (library-tree)
          sys (system root)
          h (handler sys)
          mesh-key (seed-authoring-cache! sys)
          _ (spit (io/file root hull-id "unsupported.stl") "changed")
          r (facet-post h hull-id mesh-key 0)]
      (is (= 409 (:status r)))
      (is (= :stale-mesh (:code (get (triggers r) "shipyard:facet-error"))))))

  (testing "tier-0 cache file missing"
    (let [sys (system (library-tree))
          h (handler sys)
          mesh-key (apply str (repeat 64 "1"))
          _ (swap! (:state (:library sys)) update-in [:entries hull-id] assoc :mesh-key mesh-key)
          r (facet-post h hull-id mesh-key 0)]
      (is (= 409 (:status r)))
      (is (= :mesh-not-ready (:code (get (triggers r) "shipyard:facet-error"))))))

  (testing "triangle outside the decoded mesh"
    (let [sys (system (library-tree))
          h (handler sys)
          mesh-key (seed-authoring-cache! sys)
          r (facet-post h hull-id mesh-key 2)]
      (is (= 422 (:status r)))
      (is (= :triangle-out-of-range (:code (get (triggers r) "shipyard:facet-error"))))))

  (testing "selected triangle is degenerate"
    (let [sys (system (library-tree))
          h (handler sys)
          mesh-key (seed-authoring-cache! sys (apply str (repeat 64 "3")) degenerate-mesh)
          r (facet-post h hull-id mesh-key 0)]
      (is (= 422 (:status r)))
      (is (= :degenerate-facet (:code (get (triggers r) "shipyard:facet-error"))))))

  (testing "cached bytes fail symesh validation"
    (let [sys (system (library-tree))
          h (handler sys)
          mesh-key (apply str (repeat 64 "4"))
          f (cache/tier-file (:cache sys) mesh-key 0)
          _ (do (fs/create-dirs (fs/parent f))
                (spit (fs/file f) "not a symesh")
                (swap! (:state (:library sys)) update-in [:entries hull-id] assoc :mesh-key mesh-key))
          r (facet-post h hull-id mesh-key 0)]
      (is (= 500 (:status r)))
      (is (= :invalid-mesh-cache (:code (get (triggers r) "shipyard:facet-error"))))
      (is (not (str/includes? (:body r) (str (cache/tier-file (:cache sys) mesh-key 0))))))))

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

(defn- await-job-state
  "Poll the job table until `part-id` settles on `state`.

  `await-ready` is not enough to sample it: the route serves the mesh URL as
  soon as `record-mesh-key!` has run, and `execute` records `:ready` only
  *after* that. So a caller that waits on the HTTP response can observe the
  worker mid-step - which is the same window the test below documents from the
  other side."
  [jobs part-id state]
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (loop []
      (let [actual (:state (jobs/status jobs part-id))]
        (cond
          (= state actual)                        actual
          (> (System/currentTimeMillis) deadline) actual
          :else (do (Thread/sleep 50) (recur)))))))

(deftest jobs-are-idempotent-under-concurrent-requests
  (let [sys (system (library-tree))
        h   (handler sys)
        url (str "/part/" (str/replace hull-id " " "%20"))
        rs  (->> (repeatedly 8 #(future (GET h url))) doall (mapv deref))]
    (is (every? #(= 200 (:status %)) rs))
    (await-ready h hull-id)
    (testing "one job ran, not eight"
      (is (= :ready (await-job-state (:jobs sys) hull-id :ready))))))

(deftest bulk-orientation-filters-renders-and-saves
  (let [sys (system (library-tree))
        h (handler sys)]
    (testing "the mode and filtered table are server-rendered"
      (is (str/includes? (:body (GET h "/orient")) "Bulk orientation"))
      (let [body (:body (GET h "/orient/parts" "role=hull&orientation=unset"))]
        (is (str/includes? body "Cruiser Hull"))
        (is (not (str/includes? body "Classic Ram Prow")))))
    (testing "a cold selected mesh prepares and becomes a grid entry"
      (let [deadline (+ (System/currentTimeMillis) 30000)
            ready (loop []
                    (let [response (POST h "/orient/render" {"part-ids" (pr-str [hull-id])})]
                      (cond
                        (str/includes? (:body response) "data-mesh-url") response
                        (> (System/currentTimeMillis) deadline) response
                        :else (do (Thread/sleep 50) (recur)))))]
        (is (= 200 (:status ready)))
        (is (str/includes? (:body ready) "data-bulk-part"))
        (is (str/includes? (:body ready) "Save orientations"))))
    (testing "saving writes each valid orientation through the catalog boundary"
      (let [q [0.0 0.7071067811865475 0.0 0.7071067811865476]
            response (POST h "/orient/save" {"orientations" (pr-str {hull-id q})})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Saved 1 orientation"))
        (is (= q (:part/orientation (catalog-db/part
                                     (catalog-db/snapshot! (:catalog sys)) hull-id))))))
    (testing "invalid and unknown bulk data stays recoverable"
      (is (= 422 (:status (POST h "/orient/render" {"part-ids" "broken"}))))
      (is (= 422 (:status (POST h "/orient/save" {"orientations" "{}"})))))))

(deftest an-unwritable-index-does-not-fail-a-good-part
  (testing "recording the mesh key is an optimisation. Windows CI caught this:
            a transient AccessDeniedException on the index write reported a part
            that had preprocessed perfectly as failed."
    (let [sys (system (library-tree))
          ;; An index path whose **parent** is a regular file, so
          ;; `write-atomically!` fails on its opening `create-dirs`.
          ;;
          ;; Pointing the index at a directory - the obvious fixture, and what
          ;; this test used to do - does not fail at all: `babashka.fs/move`
          ;; moves a file *into* an existing directory rather than refusing, so
          ;; the write quietly succeeded somewhere else and the test proved
          ;; nothing. Empty or not makes no difference.
          ;;
          ;; Into the state atom, not onto the component map: the index file is
          ;; part of the library's state now that the root can change, and an
          ;; `assoc-in` on the component would leave this test passing without
          ;; ever making a write fail.
          bad (let [blocker (io/file (temp-dir "shipyard-not-a-file") "blocker")]
                (spit blocker "")
                (io/file blocker "index.edn"))
          _   (swap! (:state (:library sys)) assoc :index-file bad)
          ;; Pin the premise. This test passed for a while against a *writable*
          ;; index, because it was reaching for a key that had moved - a guard
          ;; that no longer guards looks exactly like one that does.
          _   (is (thrown? Exception (index/save-index! bad "/lib" {}))
                  "the index write must actually be failing")
          h     (handler sys)
          ready (await-ready h hull-id)]
      (is (get (triggers ready) "shipyard:load-mesh"))
      ;; `not= :failed` rather than `= :ready`, and the difference is not
      ;; pedantry - Windows CI failed on the stronger form. `record-mesh-key!`
      ;; updates the index atom before it writes the file, so the mesh URL can
      ;; be served from the fast path while the worker is still inside its
      ;; retry backoff and has not recorded its result yet. What this test is
      ;; about is that the part is never reported failed; when the worker
      ;; finishes is the executor's business.
      (is (not= :failed (:state (jobs/status (:jobs sys) hull-id)))))))

(deftest missing-library-root-says-so
  (let [sys (system (library-tree))
        h   (handler sys)]
    ;; The folder is renamed, or its drive unmounted, under a running server.
    (index/set-root! (:library sys) "/no/such/library")
    (is (str/includes? (:body (GET h "/library")) "No library at"))))
