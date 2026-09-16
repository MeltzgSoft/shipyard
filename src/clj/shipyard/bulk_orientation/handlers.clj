(ns shipyard.bulk-orientation.handlers
  "Ring orchestration for filtering, preparing, and saving orientation sets."
  (:require [babashka.fs :as fs]
            [shipyard.bulk-orientation.transforms :as bulk]
            [shipyard.bulk-orientation.views :as views]
            [shipyard.catalog.db :as db]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.jobs :as jobs]
            [shipyard.http.urls :as urls]
            [shipyard.http.views :as http-views]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.workspace.db :as workspace]))

(defn- blank->nil [x]
  (when-not (or (nil? x) (= "" x)) x))

(defn- facets! [catalog]
  (let [database (db/snapshot! catalog)]
    {:bundles (db/bundles database)
     :classes (db/classes database)
     :roles (db/roles database)}))

(defn- orientation-parts [catalog params]
  (->> (db/browse (db/snapshot! catalog)
                  {:bundle (blank->nil (get params "bundle"))
                   :class (blank->nil (get params "class"))
                   :role (some-> (get params "role") blank->nil keyword)
                   :q (blank->nil (get params "q"))})
       (filter #(bulk/matches-orientation? (blank->nil (get params "orientation")) %))))

(defn orient! [{:keys [catalog]} _]
  (htmx/fragment (views/panel (facets! catalog))))

(defn parts! [{:keys [catalog workspace]} {:keys [params]}]
  (when workspace (workspace/remember! workspace :orient params))
  (htmx/fragment (views/results (orientation-parts catalog params)
                                (set (bulk/selected-ids (:selection (when workspace (workspace/workspace! workspace :orient))))))))

(defn selection! [{:keys [workspace]} {:keys [params]}]
  (let [previous (set (bulk/selected-ids (:selection (workspace/workspace! workspace :orient))))
        visible (set (bulk/selected-ids (get params "visible")))
        selected (get params "selected")
        selection (pr-str (bulk/selection-after-change previous visible
                                                       (if (string? selected) [selected] selected)))]
    (workspace/update-workspace! workspace :orient assoc :selection selection)
    (htmx/fragment (views/selection-form selection))))

(defn- grid-entry! [{:keys [library cache jobs]} part]
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

(defn render! [{:keys [catalog] :as deps} {:keys [params]}]
  (let [part-ids (bulk/selected-ids (get params "part-ids"))]
    (if-not (seq part-ids)
      (htmx/fragment [:p.detail__error "Select at least one previewable part."] {:status 422})
      (let [catalog (db/snapshot! catalog)
            entries (->> part-ids
                         (map #(db/part catalog %))
                         (filter :part/id)
                         (remove http-views/unrenderable-reason)
                         (map #(grid-entry! deps %)))]
        (if (seq entries)
          (do
            (when (:workspace deps)
              (workspace/update-workspace! (:workspace deps) :orient assoc :selection (pr-str part-ids) :grid? true))
            (htmx/fragment (views/grid (map #(merge (:part %) (dissoc % :part)) entries))))
          (htmx/fragment [:p.detail__error "None of those parts can be previewed."]
                         {:status 422}))))))

(defn save! [{:keys [catalog]} {:keys [params]}]
  (if-let [orientations (bulk/orientations-request params)]
    (let [known (db/snapshot! catalog)
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
      (htmx/fragment (views/save-result result)
                     {:status (if (seq (:failed result)) 422 200)}))
    (htmx/fragment [:p.detail__error "The bulk orientation data was invalid."] {:status 422})))
