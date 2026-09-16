(ns shipyard.workspace.db
  "Workspace-owned server selection, filters and activation ordering."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [shipyard.workspace.transforms :as transforms]))

(def modes #{:browse :orient :assembly :ships})
(def ^:dynamic *context* nil)

(defmethod ig/init-key :shipyard.workspace/db [_ {:keys [assembly preview]}]
  {:state (atom {:active :browse :activation 0
                 :workspaces (-> (zipmap modes (repeat {:filters {} :colors true}))
                                 (assoc-in [:assembly :model] assembly)
                                 (assoc-in [:ships :model] preview))})})

(defn workspace! [{:keys [state]} mode] (get-in @state [:workspaces mode]))
(defn update-workspace! [{:keys [state]} mode f & args]
  (apply swap! state update-in [:workspaces mode] f args))

(defn owner [uri]
  (cond
    (str/starts-with? uri "/orient") :orient
    (str/starts-with? uri "/assembly") :assembly
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
    (if (or (owner (:uri request)) (= "/" (:uri request))
            (str/starts-with? (:uri request) "/workspace/"))
      (locking state
        (let [incoming (context request)
              current (active-context! db)]
          (if (and incoming
                   (or (not= incoming current)
                       (not (transforms/current-request? (:activation current) (:activation incoming)
                                                         (:workspace incoming) (owner (:uri request))))))
            {:status 204 :headers {} :body ""}
            (binding [*context* current]
              (handler request)))))
      (handler request))))

(defn remember! [workspace mode params]
  (update-workspace! workspace mode update :filters merge
                     (select-keys params ["bundle" "class" "role" "q" "orientation"])))

(defn outgoing! [{:keys [workspace assembly]} params]
  (let [mode (:workspace (active-context! workspace))]
    (remember! workspace mode params)
    (when (and (= mode :assembly) (contains? params "name"))
      (swap! (:state assembly) assoc-in [:draft :name] (get params "name")))))
