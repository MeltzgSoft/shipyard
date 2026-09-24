(ns shipyard.regions.handlers
  (:require [clojure.tools.logging :as log]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.paint.strokes :as strokes]
            [shipyard.regions.model :as model]
            [shipyard.regions.migration :as migration]
            [shipyard.regions.views :as views]
            [shipyard.http.htmx :as htmx]
            [shipyard.workspace.db :as workspace]
            [shipyard.workspace.views :as workspace-views]))

(defn save! [{:keys [catalog library workspace] :as deps} {:keys [params region-selection]}]
  (let [{:strs [part-id mesh-key revision action layer name confirmed mode angle layer-revision]} params
        database (catalog/part-context! catalog part-id)
        shared-layers (catalog/region-layers database)
        part (:part database)
        before (catalog/part-regions part)
        keys (delay (when region-selection
                      (strokes/selected-face-keys (strokes/ordered-face-keys! deps mesh-key) region-selection)))
        result (cond
                 (or (nil? (:part/id part)) (not= part-id (:selection (workspace/workspace! workspace :browse))))
                 {:error "Part selection changed. Reopen the part before retrying."}
                 (#{"add" "rename" "delete"} action)
                 (if (and (= action "delete") (not= confirmed "true"))
                   {:error "Confirm deleting this layer from every part. Its regions will return to Primary."}
                   (try (catalog/edit-region-layer! catalog part-id (parse-long revision)
                                                    (some-> layer-revision (parse-long)) action layer name)
                        (catch Exception e
                          (log/error e "Could not persist region layer" {:part-id part-id :action action})
                          {:error "Could not save this layer to the database. See the server log for details."})))
                 (or (not (index/fresh-source-file! library part-id)) (not= mesh-key (index/mesh-key! library part-id)))
                 {:error "Source changed. Rescan and reopen this part before editing regions."}
                 (and (= action "assign") (nil? @keys))
                 {:error "Invalid region faces. Nothing saved."}
                 :else (model/change (or before (migration/regions (model/empty-regions mesh-key))) mesh-key (parse-long revision)
                                     (if (= action "fill") "assign" action) layer name
                                     (if (= action "fill") (vec (strokes/known-faces! deps mesh-key)) @keys)
                                     shared-layers))
        result (if (or (:error result) (#{"add" "rename" "delete"} action)) result
                   (try (assoc result :regions (catalog/save-regions! catalog part-id (:regions result) (parse-long revision)))
                        (catch Exception e
                          (log/error e "Could not persist part regions" {:part-id part-id :action action :revision revision})
                          {:error "Could not save part regions to the database. See the server log for details."})))
        saved (if (:error result) before (:regions result))
        filled? (and (= action "fill") (not (:error result)))]
    (when filled? (workspace/update-workspace! workspace :browse assoc :colors false))
    (htmx/fragment
     (list (views/panel part-id mesh-key saved (or (:selected result) layer) (:error result)
                        (catalog/region-registry! catalog) {:mode (or mode "facets") :angle (if angle (parse-long angle) 1)})
           (when filled?
             (list (workspace-views/colors-toggle false :browse)
                   (workspace-views/context (workspace/active-context! workspace) false))))
     (when filled? {:events {:display {:colors false}}}))))

(defn save-stroke! [deps request]
  (let [body (get-in request [:parameters :body])]
    (save! deps (assoc request :params (:metadata body)
                       :region-selection (select-keys body [:triangle-count :indices])))))
