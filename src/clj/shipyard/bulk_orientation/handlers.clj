(ns shipyard.bulk-orientation.handlers
  "Ring orchestration for filtering, preparing, and saving orientation sets."
  (:require [babashka.fs :as fs]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.bulk-orientation.views :as views]
            [shipyard.catalog.db :as db]
            [shipyard.part-browser.transforms :as edits]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.jobs :as jobs]
            [shipyard.http.urls :as urls]
            [shipyard.http.views :as http-views]
            [shipyard.library.index :as index]
            [shipyard.importer.db :as importer]
            [shipyard.mesh.cache :as cache]
            [shipyard.workspace.db :as workspace]))

(defn- blank->nil [x]
  (when-not (or (nil? x) (= "" x)) x))

(defn- facets! [deps]
  (let [database (importer/listing! deps)]
    {:bundles (db/bundles database)
     :classes (db/classes database)
     :roles (db/roles database)}))

(defn- orientation-parts [deps params]
  (->> (db/browse (importer/listing! deps)
                  {:bundle (blank->nil (get params "bundle"))
                   :class (blank->nil (get params "class"))
                   :role (some-> (get params "role") blank->nil keyword)
                   :q (blank->nil (get params "q"))})
       (filter #(bulk/matches-orientation? (blank->nil (get params "orientation")) %))))

(defn orient! [deps _]
  (htmx/fragment (views/panel (facets! (importer/effective! deps)))))

(defn- parts-view! [{:keys [workspace] :as deps} params]
  (when workspace (workspace/remember! workspace :browse params))
  (let [{:keys [bulk-selection filters]} (when workspace (workspace/workspace! workspace :browse))]
    (views/results (orientation-parts deps (merge filters params))
                   (set (bulk/selected-ids bulk-selection)) (get filters "table-scroll") (get filters "page"))))

(defn parts! [deps {:keys [params]}]
  (htmx/fragment (parts-view! (importer/effective! deps) params)))

(defn selection! [{:keys [workspace]} {:keys [params]}]
  (workspace/remember! workspace :browse params)
  (let [previous (set (bulk/selected-ids (:bulk-selection (workspace/workspace! workspace :browse))))
        visible (set (bulk/selected-ids (get params "visible")))
        selected (get params "selected")
        selection (pr-str (bulk/selection-after-change previous visible
                                                       (if (string? selected) [selected] selected)))]
    (workspace/update-workspace! workspace :browse assoc :bulk-selection selection)
    (htmx/fragment (views/selection-updates selection))))

(defn grid-entry! [{:keys [library cache jobs]} part]
  (let [part-id (:part/id part)
        mesh-key (index/mesh-key! library part-id)
        cached? (and mesh-key (fs/regular-file? (cache/tier-file cache mesh-key 0)))
        source (when-not cached? (index/fresh-source-file! library part-id))
        job (when source (jobs/submit! jobs part-id source))]
    (cond
      cached?
      {:part part :state :ready :mesh-key mesh-key :mesh-url (urls/mesh-url mesh-key 0)}

      (= :failed (:state job))
      {:part part :state :failed :message "Could not prepare this part."}

      (nil? source)
      {:part part :state :failed :message "The source mesh is no longer available."}

      :else
      {:part part :state :preparing :message "Preparing…"})))

(defn- render-effective! [{:keys [catalog] :as deps} {:keys [params]}]
  (let [part-ids (bulk/selected-ids (get params "part-ids"))]
    (if-not (seq part-ids)
      (htmx/fragment [:p.detail__error "Select at least one previewable part."] {:status 422})
      (let [catalog (db/listing! catalog)
            entries (->> part-ids
                         (map #(db/part catalog %))
                         (filter :part/id)
                         (remove http-views/unrenderable-reason)
                         (map #(grid-entry! deps %)))]
        (if (seq entries)
          (do
            (when (:workspace deps)
              (workspace/update-workspace! (:workspace deps) :browse assoc :bulk-selection (pr-str part-ids) :view :grid))
            (htmx/fragment (views/grid (map #(merge (:part %) (dissoc % :part)) entries))))
          (htmx/fragment [:p.detail__error "None of those parts can be previewed."]
                         {:status 422}))))))

(defn- persist! [{:keys [catalog]} orientations request activation]
  (let [known (db/listing! catalog)
        result (reduce (fn [{:keys [saved failed] :as result} [part-id part-orientation]]
                         (if-not (:part/id (db/part known part-id))
                           (assoc result :failed (conj failed part-id))
                           (try
                             (db/save-part-orientation! catalog part-id part-orientation)
                             (assoc result :saved (conj saved part-id))
                             (catch Exception _
                               (assoc result :failed (conj failed part-id))))))
                       {:saved [] :failed []}
                       orientations)]
    (htmx/fragment (views/save-result (assoc result :request request :activation activation))
                   {:status (if (seq (:failed result)) 422 200)})))

(defn- save-effective! [{:keys [workspace] :as deps} {:keys [params parameters]}]
  (if-let [orientations (bulk/orientations-request params)]
    (let [request (get-in parameters [:form :request])
          activation (:activation workspace/*context*)
          previous (:save-order (when workspace (workspace/workspace! workspace :browse)))
          order (when (and request activation) [activation request])]
      (if (not (saves/newer-request? previous order))
        {:status 204 :headers {} :body ""}
        (do
          (when order (workspace/update-workspace! workspace :browse assoc :save-order order))
          (persist! deps orientations request activation))))
    (htmx/fragment [:p.detail__error "The bulk orientation data was invalid."] {:status 422})))

(defn- metadata-effective! [{:keys [catalog workspace] :as deps} {:keys [params]}]
  (workspace/remember! workspace :browse params)
  (let [ids (bulk/selected-ids (:bulk-selection (workspace/workspace! workspace :browse)))
        database (db/listing! catalog)
        parts (mapv #(db/part database %) ids)
        result (if (some nil? parts) {:error "A selected part is unavailable. Refresh the table and retry."}
                   (edits/edits parts params))]
    (if-let [error (:error result)]
      (htmx/fragment [:span.detail__error error] {:status 422})
      (try
        (db/save-metadata! catalog (:changes result))
        (htmx/fragment
         (list [:span (str "Updated " (count ids) " part" (when (not= 1 (count ids)) "s") ".")]
               (update (parts-view! deps {}) 1 assoc :hx-swap-oob "outerHTML")
               (views/filter-updates (facets! deps) (:filters (workspace/workspace! workspace :browse)))))
        (catch Exception e (htmx/fragment [:span.detail__error (.getMessage e)] {:status 422}))))))

(defn render! [deps request]
  (render-effective! (importer/effective! deps) request))

(defn save! [deps request]
  (save-effective! (importer/effective! deps) request))

(defn metadata! [deps {:keys [params] :as request}]
  (let [deps (importer/effective! deps)]
    (if (= "variant" (get params "field"))
      (try
        (if-let [session (:import-session deps)]
          (let [ids (bulk/selected-ids (:bulk-selection (workspace/workspace! (:workspace deps) :browse)))]
            (importer/variants! session ids (keyword (get params "value")))
            (htmx/fragment (list [:span "Updated variants."]
                                 (update (parts-view! deps {}) 1 assoc :hx-swap-oob "outerHTML"))))
          (htmx/fragment [:span "Variant edits are available during import only."] {:status 422}))
        (catch Exception e (htmx/fragment [:span.detail__error (.getMessage e)] {:status 422})))
      (metadata-effective! deps request))))

(defn select-ids! [{:keys [workspace] :as deps} ids]
  (let [selection (pr-str (vec (sort ids)))
        deps (importer/effective! deps)]
    (workspace/update-workspace! workspace :browse assoc :bulk-selection selection)
    (htmx/fragment (list (views/selection-updates selection)
                         (update (parts-view! deps {}) 1 assoc :hx-swap-oob "outerHTML")))))
