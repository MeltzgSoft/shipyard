(ns shipyard.settings.handlers
  (:require [shipyard.http.htmx :as htmx]
            [shipyard.importer.db :as importer]
            [shipyard.library.index :as index]
            [shipyard.settings.db :as db]
            [shipyard.settings.transforms :as transforms]
            [shipyard.settings.views :as views]
            [shipyard.vocabulary.db :as vocabulary]
            [shipyard.workspace.db :as workspace]))

(defn current! [{:keys [catalog library workspace] :as deps} options]
  (htmx/fragment (views/panel (merge {:draft (when workspace (:draft (workspace/workspace! workspace :settings)))
                                      :root (index/root! library)
                                      :entries (vocabulary/entries! (:store catalog))
                                      :defaults (db/cut-defaults! (:store catalog))
                                      :blocked? (boolean (when workspace (importer/session! deps)))} options))
                 {:status (or (:status options) 200)}))

(defn cuts! [{:keys [catalog workspace] :as deps} {:keys [params]}]
  (let [{:keys [error values]} (transforms/cut-settings params)]
    (if error (current! deps {:error error :status 422 :draft params})
        (try (db/save-cut-defaults! (:store catalog) values)
             (when workspace (workspace/update-workspace! workspace :settings update :draft #(apply dissoc % (map first transforms/cut-fields))))
             (current! deps {:message "Saved mount-cut defaults."})
             (catch Exception e (current! deps {:error (str "Could not save defaults. " (ex-message e)) :status 422}))))))

(defn classification! [{:keys [catalog workspace] :as deps} action {:keys [params]}]
  (if (and workspace (importer/session! deps))
    (current! deps {:error "Finish or cancel the import before changing classification values." :status 409})
    (try
      (let [{:keys [error field old new]} (vocabulary/manage! (:store catalog) action (get params "field")
                                                              (get params "value") (get params (if (= action :add) "value" "new-value")))]
        (if error (current! deps {:error error :status 422})
            (do
              (when (and workspace field)
                (doseq [mode [:browse :ships]]
                  (workspace/update-workspace! workspace mode update :filters
                                               #(cond-> % (= old (get % (name field))) (assoc (name field) new)))))
              (current! deps {:message (case action :add "Added value." :rename "Renamed value and all uses." :delete "Deleted unused value.")}))))
      (catch Exception e (current! deps {:error (str "Could not update classification. " (ex-message e)) :status 422})))))
