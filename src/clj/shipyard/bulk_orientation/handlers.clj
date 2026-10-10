(ns shipyard.bulk-orientation.handlers
  "Ring orchestration for filtering, preparing, and saving orientation sets."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.bulk-orientation.views :as views]
            [shipyard.catalog.db :as db]
            [shipyard.part-browser.transforms :as edits]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.jobs :as jobs]
            [shipyard.http.pagination :as pagination]
            [shipyard.http.urls :as urls]
            [shipyard.http.views :as http-views]
            [shipyard.library.index :as index]
            [shipyard.importer.db :as importer]
            [shipyard.importer.transforms :as imports]
            [shipyard.mesh.cache :as cache]
            [shipyard.vocabulary.db :as vocabulary]
            [shipyard.vocabulary.views :as vocabulary-views]
            [shipyard.workspace.db :as workspace]))

(defn- blank->nil [x]
  (when-not (or (nil? x) (= "" x)) x))

(defn facets! [{:keys [catalog shared-catalog]}]
  (let [values (merge-with into (vocabulary/choices! catalog)
                           (when shared-catalog (vocabulary/choices! shared-catalog)))]
    {:bundles (sort (:bundle values)) :classes (sort (:class values))
     :roles (map keyword (sort (:role values))) :values values}))

(defn filtered-parts!
  "Complete ordered results, shared by the table and individual-part navigation."
  [deps params]
  (->> (db/browse (importer/listing! deps)
                  {:bundle (blank->nil (get params "bundle"))
                   :class (blank->nil (get params "class"))
                   :role (some-> (get params "role") blank->nil keyword)
                   :q (blank->nil (get params "q"))})
       (filter #(edits/listed? % (or (:import-session deps)
                                     (not (str/blank? (get params "variant")))
                                     (some (fn [[field]] (seq (get params field))) edits/variant-filters))))
       (filter #(imports/matches-variant? (when-not (= "all" (get params "variant")) (get params "variant")) %))
       (filter #(edits/matches-availability? params %))
       (filter #(bulk/matches-orientation? (blank->nil (get params "orientation")) %))))

(defn orient! [deps _]
  (htmx/fragment (views/panel (facets! (importer/effective! deps)))))

(defn- parts-view! [{:keys [workspace] :as deps} params]
  (when workspace
    (workspace/remember! workspace :browse params)
    (workspace/update-workspace! workspace :browse dissoc :part-order))
  (let [{:keys [bulk-selection filters]} (when workspace (workspace/workspace! workspace :browse))]
    (views/results (filtered-parts! deps (merge filters params))
                   (set (bulk/selected-ids bulk-selection)) (get filters "table-scroll") (get filters "page") (= "1" (get params "chunk")) (boolean (:import-session deps)))))

(defn parts! [deps {:keys [params]}]
  (if (and (= "1" (get params "chunk"))
           (not (pagination/same-filters? params (:filters (workspace/workspace! (:workspace deps) :browse)))))
    {:status 204 :headers {} :body ""}
    (htmx/fragment (parts-view! (importer/effective! deps) params))))

(defn selection! [{:keys [workspace] :as deps} {:keys [params]}]
  (workspace/remember! workspace :browse params)
  (let [deps (importer/effective! deps)
        previous (set (bulk/selected-ids (:bulk-selection (workspace/workspace! workspace :browse))))
        visible (set (bulk/selected-ids (get params "visible")))
        selected (get params "selected")
        selection (pr-str (bulk/selection-after-change previous visible
                                                       (if (string? selected) [selected] selected)))]
    (workspace/update-workspace! workspace :browse assoc :bulk-selection selection)
    (htmx/fragment
     (list (views/selection-updates selection (boolean (:import-session deps)))
           (update (views/matching-checkbox
                    (filtered-parts! deps (:filters (workspace/workspace! workspace :browse)))
                    (set (bulk/selected-ids selection)))
                   1 assoc :hx-swap-oob "outerHTML")))))

(defn grid-entry! [{:keys [library cache jobs]} part]
  (let [part-id (:part/id part)
        mesh-key (index/mesh-key! library part-id)
        cached? (and mesh-key (fs/regular-file? (cache/tier-file cache mesh-key 0)))
        source (index/fresh-source-file! library part-id)
        job (when (and source (not cached?)) (jobs/submit! jobs part-id source))]
    (cond
      (nil? source)
      {:part part :state :failed :message "The source mesh is unavailable or changed. Rescan the library."}

      cached?
      {:part part :state :ready :mesh-key mesh-key :mesh-url (urls/mesh-url mesh-key 0)}

      (= :failed (:state job))
      {:part part :state :failed :message "Could not prepare this part."}

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
  (workspace/remember! workspace :browse (select-keys params ["table-scroll" "page"]))
  (let [ids (bulk/selected-ids (:bulk-selection (workspace/workspace! workspace :browse)))
        database (db/listing! catalog)
        parts (mapv #(db/part database %) ids)
        result (if (some nil? parts) {:error "A selected part is unavailable. Refresh the table and retry."}
                   (edits/bulk-edits parts params))]
    (if-let [error (:error result)]
      (htmx/fragment [:span.detail__error error] {:status 422})
      (try
        (if (str/blank? (get params "variant"))
          (db/save-metadata! catalog (:changes result))
          (if-let [session (:import-session deps)]
            (importer/bulk-metadata! session ids (:changes result) (keyword (get params "variant")))
            (throw (ex-info "Variant edits are available during import only." {}))))
        (htmx/fragment
         (list [:span (str "Updated " (count ids) " part" (when (not= 1 (count ids)) "s") ".")]
               (update (parts-view! deps {}) 1 assoc :hx-swap-oob "outerHTML")
               (into [:div#classification-values {:hx-swap-oob "outerHTML"}]
                     (rest (vocabulary-views/choices (:values (facets! deps)))))
               (views/filter-updates (facets! deps) (:filters (workspace/workspace! workspace :browse)))))
        (catch Exception e (htmx/fragment [:span.detail__error (.getMessage e)] {:status 422}))))))

(defn render! [deps request]
  (render-effective! (importer/effective! deps) request))

(defn save! [deps request]
  (save-effective! (importer/effective! deps) request))

(defn metadata! [deps request]
  (metadata-effective! (importer/effective! deps) request))

(defn select-ids! [{:keys [workspace] :as deps} ids]
  (let [selection (pr-str (vec (sort ids)))
        deps (importer/effective! deps)]
    (workspace/update-workspace! workspace :browse assoc :bulk-selection selection)
    (htmx/fragment (list (views/selection-updates selection (boolean (:import-session deps)))
                         (update (parts-view! deps {}) 1 assoc :hx-swap-oob "outerHTML")))))

(defn select-all! [{:keys [workspace] :as deps} {:keys [params]}]
  (workspace/remember! workspace :browse params)
  (let [{:keys [filters bulk-selection]} (workspace/workspace! workspace :browse)
        previous (set (bulk/selected-ids bulk-selection))
        matching (set (map :part/id (filtered-parts! (importer/effective! deps) filters)))]
    (select-ids! deps (case (get params "selection")
                        "all" (set/union previous matching)
                        "matching-none" (set/difference previous matching)
                        "none" #{}))))

(defn row-metadata! [deps {:keys [params]}]
  (let [{:keys [catalog workspace] :as deps} (importer/effective! deps)
        id (get params "part-id")
        part (db/part (importer/listing! deps) id)
        selected (set (bulk/selected-ids (:bulk-selection (workspace/workspace! workspace :browse))))
        result (edits/row-edits part params)]
    (if-let [error (:error result)]
      (htmx/fragment [:span.detail__error error] {:status 422 :headers {"HX-Retarget" "find [role=status]" "HX-Reswap" "innerHTML"}})
      (try
        (db/save-metadata! catalog (:changes result))
        (htmx/fragment
         (list (views/orientation-row selected (db/part (importer/listing! deps) id) true "Saved.")
               (into [:div#classification-values {:hx-swap-oob "outerHTML"}]
                     (rest (vocabulary-views/choices (:values (facets! deps)))))
               (views/filter-updates (facets! deps) (:filters (workspace/workspace! workspace :browse)))))
        (catch Exception e
          (htmx/fragment [:span.detail__error (.getMessage e)]
                         {:status 422 :headers {"HX-Retarget" "find [role=status]" "HX-Reswap" "innerHTML"}}))))))

(defn row-editor! [deps {:keys [params]}]
  (let [deps (importer/effective! deps)]
    (if-let [part (db/part (importer/listing! deps) (get params "part-id"))]
      (htmx/fragment (views/row-editor part nil))
      (htmx/fragment [:p.detail__error "This part is unavailable. Refresh the table and retry."] {:status 422}))))
