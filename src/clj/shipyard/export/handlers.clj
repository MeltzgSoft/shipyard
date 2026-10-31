(ns shipyard.export.handlers
  (:require [clojure.string :as str]
            [shipyard.export.db :as db]
            [shipyard.loadout.operations :as loadouts]
            [shipyard.loadout.views :as ship-views]
            [shipyard.export.service :as service]
            [shipyard.math :as math]
            [shipyard.library.index :as index]
            [shipyard.export.transforms :as transforms]
            [shipyard.export.views :as views]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.urls :as urls]
            [shipyard.workspace.db :as workspace])
  (:import [java.nio.file Paths]))

(defn options! [{:keys [workspace]}]
  (merge {:selection #{} :priority transforms/default-priority}
         (:export (workspace/workspace! workspace :ships))))

(defn remember-options! [{:keys [workspace]} params]
  (let [options (cond-> {}
                  (contains? params "directory") (assoc :directory (get params "directory"))
                  (contains? params "diameter") (assoc :diameter (get params "diameter")))]
    (when (seq options) (workspace/update-workspace! workspace :ships update :export merge options))))

(defn current! [{:keys [exports workspace library] :as deps} _]
  (let [options (options! deps)
        finished (when exports @(:state exports))
        completed (or (when (and (= :ready (:state finished)) (= :export (:kind finished))
                                 (= (index/root! library) (get-in finished [:options :root])))
                        (:path finished)) (:completed options))]
    (when completed (workspace/update-workspace! workspace :ships assoc-in [:export :completed] completed))
    (try
      (let [snapshot (db/snapshot! deps (:selection options) (:priority options))]
        (htmx/fragment (views/panel options (:files snapshot) nil completed)))
      (catch Exception e (htmx/fragment (views/panel options nil (ex-message e) nil))))))

(defn selection! [{:keys [workspace] :as deps} {:keys [parameters params]}]
  (remember-options! deps params)
  (let [{:keys [kind id selected]} (:form parameters)
        item [(keyword kind) (parse-uuid id)]]
    (workspace/update-workspace! workspace :ships update-in [:export :selection]
                                 #(if (= selected "true") (conj (set %) item) (disj (set %) item)))
    (current! deps nil)))

(defn clear! [{:keys [workspace] :as deps} _]
  (workspace/update-workspace! workspace :ships assoc-in [:export :selection] #{})
  (let [filters (assoc (:filters (workspace/workspace! workspace :ships)) "export-selection" #{})
        results (assoc-in (ship-views/results (loadouts/list-entries! deps) filters)
                          [1 :hx-swap-oob] "outerHTML")]
    (htmx/fragment (list (views/panel (options! deps) nil "Select at least one ship to export." nil) results))))

(defn update! [{:keys [workspace] :as deps} {:keys [parameters]}]
  (let [{:keys [directory diameter move action preview]} (:form parameters)
        options (options! deps)
        [variant direction] (when move (str/split move #":"))
        options (cond-> (assoc options :directory directory :diameter diameter)
                  move (update :priority transforms/move (keyword variant) (if (= direction "up") -1 1)))]
    (workspace/update-workspace! workspace :ships assoc :export options)
    (try
      (let [parsed (when-not (str/blank? diameter) (math/parse-finite-double diameter))]
        (when (and (not (str/blank? diameter)) (not (and parsed (<= 0.1 parsed 100))))
          (throw (ex-info "Stand diameter must be between 0.1 and 100 mm." {})))
        (if (or (= action "export") preview)
          (do (service/request! deps (assoc options :parsed-diameter parsed) preview)
              (htmx/fragment [:div {:data-export-pending true :hx-get "/ships/export/status" :hx-trigger "load delay:200ms" :hx-target "#detail"}
                              [:p {:role "status"} "Preparing export geometry…"]]))
          (current! deps nil)))
      (catch Exception e (htmx/fragment (views/panel options nil (ex-message e) nil))))))

(defn- status-value! [{:keys [exports workspace] :as deps}]
  (let [{:keys [id state message path mesh-key part-id kind options]} @(:state exports)
        current (options! deps)]
    (if (not= (select-keys current [:selection :priority :diameter :directory])
              (select-keys options [:selection :priority :diameter :directory]))
      (current! deps nil)
      (case state
        :running (htmx/fragment [:div {:data-export-pending true :hx-get "/ships/export/status" :hx-trigger "load delay:200ms" :hx-target "#detail"}
                                 [:p {:role "status"} "Preparing export geometry…"]])
        :failed (htmx/fragment (views/panel current nil message nil))
        :ready (let [snapshot (db/snapshot! deps (:selection current) (:priority current))]
                 (when (= :export kind) (workspace/update-workspace! workspace :ships assoc-in [:export :completed] path))
                 (htmx/fragment (views/panel current (:files snapshot) nil path)
                                (when (= :preview kind)
                                  {:events {:load-mesh (merge workspace/*context*
                                                              {:url (urls/mesh-url mesh-key 0)
                                                               :topology-url (str "/ships/export/topology?id=" id)
                                                               :part-id part-id :mesh-key mesh-key :frame true :mounts []})}})))
        (current! deps nil)))))

(defn status! [deps _]
  (try (status-value! deps)
       (catch Exception e (htmx/fragment (views/panel (options! deps) nil (ex-message e) nil)))))

(defn topology! [{:keys [exports]} {:keys [params]}]
  (let [{:keys [id topology]} @(:state exports)]
    (if (and (= id (get params "id")) topology)
      {:status 200 :headers {"content-type" "application/octet-stream" "cache-control" "no-store"}
       :body (java.io.ByteArrayInputStream. topology)}
      {:status 404 :headers {} :body "Preview expired. Preview the model again."})))

(defn download! [{:keys [workspace]} _]
  (if-let [path (get-in (workspace/workspace! workspace :ships) [:export :completed])]
    {:status 200 :headers {"content-type" "application/zip" "content-disposition" "attachment; filename=ships.zip"}
     :body (.toFile (Paths/get path (make-array String 0)))}
    {:status 404 :headers {} :body "No completed export."}))
