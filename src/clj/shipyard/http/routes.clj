(ns shipyard.http.routes
  "The route table and its handlers (TECHNICAL.md §7).

  Every handler is a function of `deps` and a request, closed over by `partial`
  at router build time. `deps` is the component map integrant assembled, so the
  handler tree is a pure function of its dependencies and can be exercised
  without a socket."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [ring.middleware.params :as params]
            [shipyard.catalog.db :as db]
            [shipyard.http.contracts :as contracts]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.jobs :as jobs]
            [shipyard.http.settings :as settings]
            [shipyard.http.urls :as urls]
            [shipyard.http.views :as views]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.mesh.facet :as facet]
            [shipyard.mount.wizard :as wizard]
            [shipyard.part.orientation :as orientation]
            [shipyard.wire :as wire])
  (:import [java.io ByteArrayOutputStream FileInputStream]))

(defn- healthz [_]
  {:status  200
   :headers {"content-type" "application/json"
             "cache-control" htmx/fragment-cache-control}
   :body    "{\"status\":\"ok\"}"})

;; --- shell ------------------------------------------------------------------

(defn- facets!
  "The filter menus. Read from one db value so the three of them cannot
  disagree, and computed once per page load - the library does not change while
  the process runs."
  [catalog]
  (let [db (db/snapshot! catalog)]
    {:bundles (db/bundles db)
     :classes (db/classes db)
     :roles   (db/roles db)}))

(defn- root! [{:keys [catalog library]} _]
  (htmx/page (views/shell (facets! catalog) (index/root! library))))

;; --- library ----------------------------------------------------------------

(defn- blank->nil [s] (when-not (str/blank? s) s))

(defn- library!
  "`GET /library`. An absent or empty parameter means no filter, which is what
  the \"All bundles\" option submits."
  [{:keys [catalog library]} {:keys [params]}]
  (if-not (index/available?! library)
    (htmx/fragment (if (index/root! library)
                     (views/library-unavailable (index/root! library))
                     (views/library-needs-root)))
    (htmx/fragment
     (views/library-results
      (db/browse (db/snapshot! catalog)
                 {:bundle (blank->nil (get params "bundle"))
                  :class  (blank->nil (get params "class"))
                  :role   (some-> (get params "role") blank->nil keyword)
                  :q      (blank->nil (get params "q"))})))))

;; --- part detail ------------------------------------------------------------

(defn- durable-mounts [part]
  (mapv #(dissoc % :db/id) (:part/mounts part)))

(defn- source-file!
  "The STL the mesh pipeline should open. Derived from the catalog record, never
  from the URL: the id in the path only ever selects a part, it never names a
  file."
  [library {:part/keys [id source]}]
  (fs/file (index/root! library) id (index/name-of source)))

(defn- ready
  "The mesh is on disk. The fragment says so and the `HX-Trigger` hands the
  viewport the URL - the canvas is never swapped, so this header is the only
  channel to it (§7.1)."
  [part mesh-key]
  (htmx/fragment (views/detail-ready part mesh-key)
                 {:events {:load-mesh {:url     (urls/mesh-url mesh-key 0)
                                       :part-id (:part/id part)
                                       :mesh-key mesh-key
                                       :mounts  (durable-mounts part)
                                       :orientation (orientation/orientation-of
                                                     (:part/orientation part))
                                       :frame   true}}}))

(defn- preprocessing!
  "Submit the job if it is not already running and answer with whatever is true
  right now. This returns in milliseconds even when the work takes seconds,
  which is the whole point of §7's preprocess-latency rule."
  [{:keys [library jobs]} part]
  (let [id (:part/id part)
        {:keys [state mesh-key message]} (jobs/submit! jobs id (source-file! library part))]
    (case state
      :ready  (ready part mesh-key)
      :failed (htmx/fragment (views/detail-failed part message)
                             {:events {:status {:state :failed :message message}}})
      (htmx/fragment (views/detail-preparing part)
                     {:events {:status {:state   :preparing
                                        :message "Preparing this part for display."}}}))))

(defn- part!
  "`GET /part/*id`. A catch-all rather than `:id` because a part id contains
  separators; see `shipyard.http.urls/encode-id` for why `%2F` is not an option.

  `?retry=1` is the only way a failed job runs again. The poll fragment does not
  carry it, so a failure is shown rather than silently retried on the next tick."
  [{:keys [catalog library cache jobs] :as deps} {:keys [params path-params]}]
  (let [id   (:id path-params)
        part (db/part (db/snapshot! catalog) id)]
    (cond
      (nil? (:part/id part))
      (htmx/fragment (views/detail-missing id) {:status 404 :events {:clear nil}})

      (views/unrenderable-reason part)
      (htmx/fragment (views/detail-unrenderable part (views/unrenderable-reason part))
                     {:events {:clear nil}})

      :else
      (let [_      (when (get params "retry") (jobs/forget! jobs id))
            cached (index/mesh-key! library id)]
        (if (and cached (fs/regular-file? (cache/tier-file cache cached 0)))
          ;; Known from a previous run: no job, no poll, no round trip.
          (ready part cached)
          (preprocessing! deps part))))))

;; --- facet preview ----------------------------------------------------------

(def ^:private mesh-key-re #"[0-9a-f]{64}")

(defn- parse-triangle-index [s]
  (when (and (string? s) (re-matches #"[0-9]+" s))
    (try
      (parse-long s)
      (catch NumberFormatException _ nil))))

(defn- facet-error
  ([code message] (facet-error code message nil 400))
  ([code message part-id status]
   (htmx/fragment (views/facet-error message)
                  {:status status
                   :events {:facet-error {:code code
                                          :message message
                                          :part-id part-id}}})))

(defn- invalid-selection [part-id]
  (facet-error :invalid-selection "Select a face from the loaded part." part-id 400))

(defn- read-bytes! [f]
  (with-open [in (FileInputStream. (fs/file f))
              out (ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn- fresh-entry?! [entry source]
  (try
    (index/fresh-source?! entry source)
    (catch Exception _
      false)))

(defn- facet-preview!
  "`POST /facet`. Turns a selected tier-0 triangle into a transient preview
  event. Durable mount writes are a later M2 endpoint."
  [{:keys [catalog library cache]} {:keys [params]}]
  (let [part-id (get params "part-id")
        mesh-key (get params "mesh-key")
        triangle-index (parse-triangle-index (get params "triangle-index"))]
    (if-not (and (seq part-id)
                 (string? mesh-key)
                 (re-matches mesh-key-re mesh-key)
                 triangle-index)
      (invalid-selection part-id)
      (let [db (db/snapshot! catalog)
            part (db/part db part-id)]
        (cond
          (nil? (:part/id part))
          (facet-error :part-not-found "That part is no longer in the library." part-id 404)

          :else
          (let [{:keys [root entry]} (index/part-state! library part-id)
                current-key (:mesh-key entry)
                source (when (and root (:part/source part))
                         (fs/file root part-id (index/name-of (:part/source part))))
                tier0 (when current-key (cache/tier-file cache current-key 0))]
            (cond
              (nil? current-key)
              (facet-error :mesh-not-ready "Open the part and wait for preprocessing to finish." part-id 409)

              (not= mesh-key current-key)
              (facet-error :stale-mesh "The mesh changed. Reopen the part before picking a face." part-id 409)

              (not (fresh-entry?! entry source))
              (facet-error :stale-mesh "The source STL changed. Reopen the part before picking a face." part-id 409)

              (not (fs/regular-file? tier0))
              (facet-error :mesh-not-ready "Open the part and wait for preprocessing to finish." part-id 409)

              :else
              (try
                (let [{:keys [facet-indices frame]}
                      (facet/select (wire/decode (read-bytes! tier0)) triangle-index
                                    (select-keys cache [:facet-angle-deg :facet-plane-epsilon-mm]))
                      frame (orientation/orient-mount-frame frame (:part/orientation part))
                      edit (when-let [original-mount-id (get params "original-mount-id")]
                             (wizard/edit-request {"mount-id" original-mount-id}
                                                  (durable-mounts part)))
                      preview (cond-> {:part part
                                       :frame frame
                                       :values (merge (:values edit)
                                                      (wizard/preview-values params))}
                                (:mount edit)
                                (assoc :mode :edit
                                       :original-mount-id (:original-mount-id edit)))]
                  (htmx/fragment
                   (views/facet-preview preview)
                   {:events {:facet-preview {:part-id part-id
                                             :mesh-key mesh-key
                                             :triangle-index triangle-index
                                             :facet-indices facet-indices
                                             :frame frame
                                             :roll-ambiguous? false
                                             :roll-source :part-orientation}}}))
                (catch clojure.lang.ExceptionInfo e
                  (case (:code (ex-data e))
                    :triangle-out-of-range
                    (facet-error :triangle-out-of-range "Pick a face on the loaded mesh." part-id 422)

                    :degenerate-facet
                    (facet-error :degenerate-facet "Pick a different face; that one cannot define a mount." part-id 422)

                    :invalid-mesh-cache
                    (facet-error :invalid-mesh-cache "The cached mesh is invalid. Reopen the part to rebuild it." part-id 500)

                    (facet-error :invalid-mesh-cache "The cached mesh is invalid. Reopen the part to rebuild it." part-id 500)))))))))))

;; --- durable mounts ---------------------------------------------------------

(defn- mount-response!
  ([deps part-id events] (mount-response! deps part-id events nil))
  ([{:keys [catalog library]} part-id events view-options]
   (let [part (db/part (db/snapshot! catalog) part-id)
         mesh-key (index/mesh-key! library part-id)]
     (if (and (:part/id part) mesh-key)
       (htmx/fragment (views/detail-ready part mesh-key view-options)
                      {:events (assoc events :interfaces {:part-id part-id
                                                          :mesh-key mesh-key
                                                          :mounts (durable-mounts part)})})
       (if (:part/id part)
         (facet-error :mesh-not-ready "Open the part and wait for preprocessing to finish." part-id 409)
         (facet-error :part-not-found "That part is no longer in the library." part-id 404))))))

(defn- mount-error-response!
  [{:keys [library] :as deps} part-id error events view-options]
  (if (index/mesh-key! library part-id)
    (mount-response! deps part-id events view-options)
    (htmx/fragment (views/facet-error error))))

(defn- save-mount!
  [{:keys [catalog library] :as deps} {:keys [params]}]
  (let [part-id (get params "part-id")
        part (db/part (db/snapshot! catalog) part-id)]
    (cond
      (nil? (:part/id part))
      (facet-error :part-not-found "That part is no longer in the library." part-id 404)

      :else
      (let [result (wizard/save-request params
                                        (durable-mounts part)
                                        (:part/orientation part))]
        (if-let [error (:error result)]
          (mount-error-response!
           deps
           part-id
           error
           {:authoring {:state :enter
                        :part-id part-id
                        :mesh-key (index/mesh-key! library part-id)}}
           {:preview (wizard/error-preview part params error)})
          (try
            (db/save-authoring! catalog part-id (select-keys result [:mounts]))
            (if-let [repeat-values (:repeat-values result)]
              (mount-response! deps part-id {:clear-preview nil
                                             :authoring {:state :enter
                                                         :part-id part-id
                                                         :mesh-key (index/mesh-key! library part-id)}
                                             :mount-repeat repeat-values}
                               {:repeat-values repeat-values})
              (mount-response! deps part-id {:clear-preview nil
                                             :authoring {:state :exit}}))
            (catch Exception _
              (facet-error :mount-save-failed
                           "The mount was written, but the catalog did not update. Restart Shipyard to re-ingest it."
                           part-id
                           500))))))))

(defn- edit-mount!
  [{:keys [catalog library] :as deps} {:keys [params]}]
  (let [part-id (get params "part-id")
        part (db/part (db/snapshot! catalog) part-id)
        mesh-key (index/mesh-key! library part-id)]
    (cond
      (nil? (:part/id part))
      (facet-error :part-not-found "That part is no longer in the library." part-id 404)

      (nil? mesh-key)
      (facet-error :mesh-not-ready "Open the part and wait for preprocessing to finish." part-id 409)

      :else
      (let [result (wizard/edit-request params (durable-mounts part))]
        (if-let [error (:error result)]
          (mount-error-response! deps
                                 part-id
                                 error
                                 {}
                                 {:error error})
          (let [frame (:frame result)]
            (mount-response!
             deps
             part-id
             {:authoring {:state :enter
                          :part-id part-id
                          :mesh-key mesh-key}
              :facet-preview {:part-id part-id
                              :mesh-key mesh-key
                              :frame frame
                              :roll-source :saved-mount}}
             {:preview {:part part
                        :frame frame
                        :mode :edit
                        :original-mount-id (:original-mount-id result)
                        :values (:values result)}})))))))

(defn- save-part-role!
  [{:keys [catalog] :as deps} {:keys [params]}]
  (let [part-id (get params "part-id")
        part (db/part (db/snapshot! catalog) part-id)]
    (cond
      (nil? (:part/id part))
      (facet-error :part-not-found "That part is no longer in the library." part-id 404)

      :else
      (let [result (wizard/part-role-request params)]
        (if-let [error (:error result)]
          (mount-response! deps part-id {} {:error error})
          (try
            (db/save-part-role! catalog part-id (:part-role result))
            (mount-response! deps part-id {})
            (catch Exception _
              (facet-error :part-role-save-failed
                           "The part role was written, but the catalog did not update. Restart Shipyard to re-ingest it."
                           part-id
                           500))))))))

(defn- save-part-orientation!
  [{:keys [catalog] :as deps} {:keys [params]}]
  (let [part-id (get params "part-id")
        part (db/part (db/snapshot! catalog) part-id)]
    (cond
      (nil? (:part/id part))
      (facet-error :part-not-found "That part is no longer in the library." part-id 404)

      :else
      (let [result (orientation/save-request params)]
        (if-let [error (:error result)]
          (mount-response! deps
                           part-id
                           {:part-orientation {:part-id part-id
                                               :orientation (orientation/orientation-of
                                                             (:part/orientation part))}}
                           {:orientation-error error})
          (try
            (let [part-orientation (db/save-part-orientation!
                                    catalog part-id (:orientation result))]
              (mount-response! deps
                               part-id
                               {:part-orientation {:part-id part-id
                                                   :saved? true
                                                   :orientation part-orientation}}))
            (catch Exception _
              (facet-error :part-orientation-save-failed
                           "The part orientation was written, but the catalog did not update. Restart Shipyard to re-ingest it."
                           part-id
                           500))))))))

(defn- delete-mount!
  [{:keys [catalog] :as deps} {:keys [params]}]
  (let [part-id (get params "part-id")
        part (db/part (db/snapshot! catalog) part-id)]
    (cond
      (nil? (:part/id part))
      (facet-error :part-not-found "That part is no longer in the library." part-id 404)

      :else
      (let [existing (durable-mounts part)
            result (wizard/delete-request params existing)]
        (cond
          (:error result)
          (mount-error-response! deps part-id (:error result) {} {:error (:error result)})

          (= existing (:mounts result))
          (mount-error-response! deps
                                 part-id
                                 "No mount with that id exists."
                                 {}
                                 {:error "No mount with that id exists."})

          :else
          (try
            (db/save-authoring! catalog part-id (select-keys result [:mounts]))
            (mount-response! deps part-id {:clear-preview nil})
            (catch Exception _
              (facet-error :mount-save-failed
                           "The mount deletion was written, but the catalog did not update. Restart Shipyard to re-ingest it."
                           part-id
                           500))))))))

;; --- settings ---------------------------------------------------------------

(defn- save-settings!
  "`POST /settings`. Applies a new library root, or explains why it did not.

  A success answers `HX-Refresh` rather than a fragment. The library has been
  replaced wholesale - the filter facets in the shell were built from the old
  one, and so was every part id the detail panel and the viewport are holding -
  and re-rendering only the results list would leave a page describing two
  different libraries at once."
  [deps {:keys [params]}]
  (if-let [refused (settings/relocate! deps (get params "root"))]
    (htmx/fragment [:p.detail__error refused] {:status 422})
    {:status  204
     :headers {"HX-Refresh"    "true"
               "cache-control" htmx/fragment-cache-control}
     :body    ""}))

;; --- mesh -------------------------------------------------------------------

(defn- not-found [message]
  {:status  404
   :headers {"content-type" "text/plain; charset=utf-8"
             "cache-control" htmx/fragment-cache-control}
   :body    message})

(defn- mesh!
  "`GET /mesh/<sha256>.<tier>.symesh`. The regex is the whole of the access
  control: only a hex digest and a small integer ever reach the filesystem."
  [{:keys [cache]} {:keys [path-params]}]
  (let [[_ mesh-key tier] (re-matches urls/mesh-file-re (str (:file path-params)))
        f (when mesh-key (cache/tier-file cache mesh-key (parse-long tier)))]
    (if (and f (fs/regular-file? f))
      {:status  200
       :headers {"content-type"   "application/octet-stream"
                 "content-length" (str (fs/size f))
                 "cache-control"  htmx/immutable-cache-control}
       :body    (fs/file f)}
      (not-found "no such mesh"))))

;; --- router -----------------------------------------------------------------

(defn routes [deps]
  [["/" {:get {:handler (partial root! deps)
               :responses contracts/html-responses}}]
   ["/healthz" {:get {:handler healthz
                      :responses contracts/health-responses}}]
   ["/library" {:get {:handler (partial library! deps)
                      :parameters {:query contracts/library-query}
                      :responses contracts/html-responses}}]
   ["/settings" {:post {:handler (partial save-settings! deps)
                        :parameters {:form contracts/settings-form}
                        :responses contracts/html-responses}}]
   ["/facet" {:post {:handler (partial facet-preview! deps)
                     :parameters {:form contracts/facet-form}
                     :responses contracts/html-responses}}]
   ["/mounts" {:post {:handler (partial save-mount! deps)
                      :parameters {:form contracts/mount-form}
                      :responses contracts/html-responses}}]
   ["/mounts/edit" {:post {:handler (partial edit-mount! deps)
                           :parameters {:form contracts/mount-id-form}
                           :responses contracts/html-responses}}]
   ["/mounts/delete" {:post {:handler (partial delete-mount! deps)
                             :parameters {:form contracts/mount-id-form}
                             :responses contracts/html-responses}}]
   ["/parts/role" {:post {:handler (partial save-part-role! deps)
                          :parameters {:form contracts/role-form}
                          :responses contracts/html-responses}}]
   ["/parts/orientation" {:post {:handler (partial save-part-orientation! deps)
                                 :parameters {:form contracts/orientation-form}
                                 :responses contracts/html-responses}}]
   ["/part/*id" {:get {:handler (partial part! deps)
                       :parameters {:path contracts/part-path}
                       :responses contracts/html-responses}}]
   ["/mesh/:file" {:get {:handler (partial mesh! deps)
                         :parameters {:path contracts/mesh-path}
                         :responses contracts/mesh-responses}}]])

(defn router [deps]
  (ring/router
   (routes deps)
   {:data {:coercion malli-coercion/coercion
           :middleware [params/wrap-params
                        coercion/coerce-exceptions-middleware
                        coercion/coerce-request-middleware
                        coercion/coerce-response-middleware]}}))

(defn handler
  "Build the ring handler. `deps` carries :library, :catalog, :cache and :jobs,
  and may carry :config-dir - injectable so a test can relocate the library
  without writing into the developer's real config."
  [deps]
  (ring/ring-handler
   (router deps)
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))))

(defmethod ig/init-key :shipyard.http/routes [_ opts]
  (handler opts))
