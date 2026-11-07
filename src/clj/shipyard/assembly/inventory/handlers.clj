(ns shipyard.assembly.inventory.handlers
  "Assembly-owned, cancellable inventory staging; never changes Part Browser state."
  (:require [shipyard.assembly.db :as assembly]
            [shipyard.assembly.handlers :as assembly-handlers]
            [shipyard.assembly.inventory.transforms :as transforms]
            [shipyard.assembly.inventory.views :as views]
            [shipyard.assembly.responses :as responses]
            [shipyard.bulk-orientation.handlers :as browser]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.pagination :as pagination]
            [shipyard.workspace.db :as workspace]))

(defn- error-response [error]
  (htmx/fragment [:p.detail__error (get responses/messages error)]
                 {:status (if (= :stale-revision error) 409 422)
                  :headers {"HX-Retarget" "#extras-picker-status" "HX-Reswap" "innerHTML"}}))

(defn open! [{{state :state} :assembly :keys [catalog workspace] :as deps} _]
  (locking state
    (let [draft (:draft @state)
          hull (catalog/part (catalog/assembly-snapshot! catalog) (:hull draft))]
      (if-not hull (error-response :no-draft)
              (let [picker {:token (str (random-uuid)) :revision (:revision draft) :hull (:hull draft)
                            :quantities (or (:extras draft) {})
                            :filters {"bundle" (:part/bundle hull) "class" (:part/class hull)}}]
                (workspace/update-workspace! workspace :ships assoc :extras-picker picker)
                (htmx/fragment (views/modal picker (browser/facets! deps)
                                            (browser/filtered-parts! deps (assoc (:filters picker) "variant" "all")))))))))

(defn- current-picker! [{:keys [assembly workspace]} params]
  (let [picker (:extras-picker (workspace/workspace! workspace :ships))
        draft (:draft @(:state assembly))]
    (when (transforms/current? picker draft (get params "token"))
      picker)))

(defn filter! [{{state :state} :assembly :keys [workspace] :as deps} {:keys [params]}]
  (locking state
    (if-let [picker (current-picker! deps params)]
      (let [result (transforms/quantities (:quantities picker) params)]
        (if-let [error (:error result)] (error-response error)
                (let [filters (select-keys params pagination/filter-keys)
                      picker (assoc picker :quantities (:quantities result) :filters filters)]
                  (workspace/update-workspace! workspace :ships assoc :extras-picker picker)
                  (htmx/fragment (views/results (browser/filtered-parts! deps (assoc filters "variant" "all"))
                                                (:quantities picker) (get params "page"))))))
      (error-response :stale-revision))))

(defn add! [{{state :state} :assembly :keys [workspace] :as deps} {:keys [params]}]
  (locking state
    (if-let [picker (current-picker! deps params)]
      (let [{:keys [quantities error]} (transforms/quantities (:quantities picker) params)]
        (if error (error-response error)
            (let [result (assembly/request! (assoc deps :paint-profile nil)
                                            {:op :extras :revision (:revision picker) :extras quantities
                                             :name (get params "name")} {})]
              (if-let [error (:error result)] (error-response error)
                      (do (workspace/update-workspace! workspace :ships dissoc :extras-picker)
                          (update (assembly-handlers/response! deps result) :headers merge
                                  {"HX-Retarget" "#detail" "HX-Reswap" "innerHTML"}))))))
      (error-response :stale-revision))))

(defn cancel! [{:keys [workspace]} {:keys [params]}]
  (if (= (get params "token") (:token (:extras-picker (workspace/workspace! workspace :ships))))
    (do (workspace/update-workspace! workspace :ships dissoc :extras-picker)
        (htmx/fragment ""))
    {:status 204 :headers {} :body ""}))
