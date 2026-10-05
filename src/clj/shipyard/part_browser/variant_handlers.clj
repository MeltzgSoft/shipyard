(ns shipyard.part-browser.variant-handlers
  (:require [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.bulk-orientation.transforms :as selection]
            [shipyard.http.htmx :as htmx]
            [shipyard.part-browser.variants :as variants]
            [shipyard.workspace.db :as workspace]))

(defn edit! [{:keys [workspace] :as deps} {:keys [params]} action]
  (try
    (let [ids (selection/selected-ids (:bulk-selection (workspace/workspace! workspace :browse)))
          selected (variants/edit! deps action {:ids ids :group (get params "group") :file (get params "file")
                                                :variant (some-> (get params "variant") keyword) :name (get params "name")})
          response (bulk/select-ids! deps selected)]
      (update response :body str "<p id=\"variant-status\" hx-swap-oob=\"outerHTML\" role=\"status\">Variants updated.</p>"))
    (catch Exception e
      (htmx/fragment [:p#variant-status {:role "status" :hx-swap-oob "outerHTML" :class "detail__error"} (.getMessage e)] {:status 422}))))
