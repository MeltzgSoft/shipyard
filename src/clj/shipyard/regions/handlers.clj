(ns shipyard.regions.handlers
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.paint.strokes :as strokes]
            [shipyard.regions.model :as model]
            [shipyard.regions.views :as views]
            [shipyard.http.htmx :as htmx]
            [shipyard.workspace.db :as workspace]
            [shipyard.workspace.views :as workspace-views]))

(defn save! [{:keys [catalog library workspace] :as deps} {:keys [params]}]
  (let [{:strs [part-id mesh-key revision action layer name faces]} params
        database (catalog/snapshot! catalog)
        shared-layers (catalog/region-layers database)
        part (catalog/part database part-id)
        before (catalog/part-regions part)
        keys (strokes/parse-faces faces)
        result (cond
                 (or (nil? (:part/id part)) (not= part-id (:selection (workspace/workspace! workspace :browse))))
                 {:error "Part selection changed. Reopen the part before retrying."}
                 (or (not (index/fresh-source-file! library part-id)) (not= mesh-key (index/mesh-key! library part-id)))
                 {:error "Source changed. Rescan and reopen this part before editing regions."}
                 (and (= action "assign") (or (nil? keys) (not-every? (strokes/known-faces! deps mesh-key) keys)))
                 {:error "Invalid region faces. Nothing saved."}
                 :else (model/change before mesh-key (parse-long revision)
                                     (if (= action "fill") "assign" action) layer name
                                     (if (= action "fill") (vec (strokes/known-faces! deps mesh-key)) keys)
                                     shared-layers))
        result (if (:error result) result
                   (try (catalog/save-regions! catalog part-id (:regions result)) result
                        (catch Exception _ {:error "Could not save part regions. Check the part folder permissions and retry."})))
        saved (if (:error result) before (:regions result))
        filled? (and (= action "fill") (not (:error result)))]
    (when filled? (workspace/update-workspace! workspace :browse assoc :colors false))
    (htmx/fragment
     (list (views/panel part-id mesh-key saved (if (#{"add" "rename"} action) name layer) (:error result)
                        (catalog/region-layers (catalog/snapshot! catalog)))
           (when filled?
             (list (workspace-views/colors-toggle false :browse)
                   (workspace-views/context (workspace/active-context! workspace) false))))
     (when filled? {:events {:display {:colors false}}}))))
