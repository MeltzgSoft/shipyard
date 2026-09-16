(ns shipyard.workspace.db
  "Workspace-owned server selection, filters and activation ordering."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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

(defn wrap [handler {:keys [state]}]
  (fn [request]
    (if-let [{:keys [workspace activation] :as context} (context request)]
      (locking state
        (if-not (transforms/current-request? (:activation @state) activation workspace (owner (:uri request)))
          {:status 204 :headers {} :body ""}
          (do
            (swap! state assoc :active workspace :activation activation)
            (when (and (owner (:uri request)) (get-in request [:headers "x-shipyard-colors"]))
              (swap! state assoc-in [:workspaces workspace :colors]
                     (= "true" (get-in request [:headers "x-shipyard-colors"]))))
            (binding [*context* context]
              (let [response (handler request)]
                (update response :headers merge
                        {"X-Shipyard-Workspace" (name workspace)
                         "X-Shipyard-Activation" (str activation)}))))))
      (handler request))))

(defn remember! [workspace mode params]
  (update-workspace! workspace mode update :filters merge
                     (select-keys params ["bundle" "class" "role" "q" "orientation"])))

(defn outgoing! [{:keys [workspace assembly]} params]
  (let [mode (keyword (get params "from" "browse"))]
    (when (modes mode)
      (when-let [filters (get params "filters")]
        (try (remember! workspace mode (json/read-str filters)) (catch Exception _ nil)))
      (update-workspace! workspace mode assoc :colors (= "true" (get params "colors" "true")))
      (when (= mode :orient)
        (update-workspace! workspace mode assoc :selection (get params "selection" "[]") :grid? (= "true" (get params "grid"))))
      (when (and (= mode :assembly) (contains? params "draft-name"))
        (swap! (:state assembly) assoc-in [:draft :name] (get params "draft-name"))))))
