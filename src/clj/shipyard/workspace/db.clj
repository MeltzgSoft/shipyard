(ns shipyard.workspace.db
  "Workspace-owned server selection, filters and activation ordering."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [shipyard.importer.db :as importer]
            [shipyard.workspace.transforms :as transforms]))

(def modes #{:browse :ships})
(def ^:dynamic *context* nil)
(def ^:dynamic *scene-sequence* nil)

(defmethod ig/init-key :shipyard.workspace/db [_ {:keys [assembly preview]}]
  {:state (atom {:active :browse :activation 0
                 :workspaces (-> (zipmap modes (repeat {:filters {} :colors true}))
                                 (assoc-in [:ships :assembly] assembly)
                                 (assoc-in [:ships :model] preview)
                                 (assoc-in [:ships :colors] false))})})

(defn workspace! [{:keys [state]} mode] (get-in @state [:workspaces mode]))
(defn update-workspace! [{:keys [state]} mode f & args]
  (apply swap! state update-in [:workspaces mode] f args))

(defn owner [uri]
  (cond
    (or (str/starts-with? uri "/orient") (str/starts-with? uri "/imports") (= uri "/classifications")) :browse
    (str/starts-with? uri "/assembly") :ships
    (str/starts-with? uri "/ships") :ships
    (or (= uri "/library") (str/starts-with? uri "/part/")
        (str/starts-with? uri "/parts/") (str/starts-with? uri "/mounts") (= uri "/facet")) :browse))

(defn context [request]
  (let [mode (some-> (get-in request [:headers "x-shipyard-workspace"]) keyword)
        activation (some-> (get-in request [:headers "x-shipyard-activation"]) parse-long)]
    (when (and (modes mode) activation) {:workspace mode :activation activation})))

(defn active-context! [{:keys [state]}]
  (let [{:keys [active activation]} @state]
    {:workspace active :activation activation}))

(defn activate! [{:keys [state] :as workspace} mode]
  (swap! state #(-> % (assoc :active mode) (update :activation inc)))
  (active-context! workspace))

(defn wrap [handler {:keys [state] :as db}]
  (fn [request]
    ;; One server boundary orders transitions and mutations. The browser echoes
    ;; a rendered generation; it can neither mint nor advance that generation.
    (if (or (owner (:uri request)) (= "/" (:uri request)) (= "/settings" (:uri request))
            (str/starts-with? (:uri request) "/workspace/"))
      (locking state
        (let [incoming (context request)
              current (active-context! db)]
          (if (or (and (get-in request [:headers "x-shipyard-workspace"]) (nil? incoming))
                  (and incoming
                       (or (not= incoming current)
                           (not (transforms/current-request? (:activation current) (:activation incoming)
                                                             (:workspace incoming) (owner (:uri request)))))))
            {:status 204 :headers {} :body ""}
            (binding [*context* current
                      *scene-sequence* (some-> (get-in request [:headers "x-shipyard-scene-sequence"]) parse-long)]
              (when (= :ships (:workspace current))
                (when-let [scroll (some-> (get-in request [:headers "x-shipyard-assembly-scroll"]) parse-double)]
                  (when (<= 0 scroll 100000000)
                    (update-workspace! db :ships assoc :assembly-scroll scroll))))
              (if (and (get-in @state [:workspaces :browse :import])
                       (or (= "/settings" (:uri request))
                           (= "/facet" (:uri request))
                           (str/starts-with? (:uri request) "/mounts")
                           (str/starts-with? (:uri request) "/part/")
                           (str/starts-with? (:uri request) "/parts/regions")
                           (= "/parts/role" (:uri request))
                           (= "/parts/orientation" (:uri request))))
                {:status 409 :headers {"content-type" "text/html"} :body "Mount authoring and region painting are unavailable during import. Finish or cancel the import first."}
                (handler request))))))
      (handler request))))

(defn remember! [workspace mode params]
  (update-workspace! workspace mode update :filters merge
                     (select-keys params ["bundle" "class" "role" "q" "orientation" "table-scroll" "expanded" "page"])))

(defn outgoing! [{:keys [workspace assembly]} params]
  (let [mode (:workspace (active-context! workspace))]
    (when-not (and (= mode :ships) (= :editor (:view (workspace! workspace :ships))))
      (remember! workspace mode params))
    (when (and (= mode :ships) (= "assembly" (:inspector-tab (workspace! workspace :ships))) (contains? params "name"))
      (swap! (:state assembly) assoc-in [:draft :name] (get params "name")))))

(defmethod ig/halt-key! :shipyard.workspace/db [_ db]
  (when-let [session (get-in @(:state db) [:workspaces :browse :import])]
    (importer/close! session)))
