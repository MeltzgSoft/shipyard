(ns shipyard.settings.handlers
  (:require [shipyard.http.htmx :as htmx]
            [shipyard.importer.db :as importer]
            [shipyard.library.index :as index]
            [shipyard.settings.db :as db]
            [shipyard.settings.export-directory :as export-directory]
            [shipyard.settings.appearance-views :as appearance]
            [shipyard.settings.transforms :as transforms]
            [shipyard.settings.views :as views]
            [shipyard.vocabulary.db :as vocabulary]
            [shipyard.workspace.db :as workspace]))

(defn current! [{:keys [catalog library workspace] :as deps} options]
  (let [theme (db/theme! (:store catalog))]
    (htmx/fragment (list (views/panel (merge {:draft (when workspace (:draft (workspace/workspace! workspace :settings)))
                                              :theme theme
                                              :root (index/root! library)
                                              :export-root (db/export-root! (:store catalog))
                                              :variant-preference (db/variant-preference! (:store catalog))
                                              :entries (vocabulary/entries! (:store catalog))
                                              :defaults (db/cut-defaults! (:store catalog))
                                              :blocked? (boolean (when workspace (importer/session! deps)))} options))
                         (appearance/state theme true))
                   {:status (or (:status options) 200)})))

(defn theme! [{:keys [catalog workspace] :as deps} {:keys [params]}]
  (when workspace (workspace/outgoing! deps params))
  (try
    (db/save-theme! (:store catalog) (keyword (get params "theme")))
    (current! deps {:message "Saved appearance."})
    (catch Exception e (current! deps {:error (str "Could not save appearance. " (ex-message e)) :status 422}))))

(defn cuts! [{:keys [catalog workspace] :as deps} {:keys [params]}]
  (when workspace (workspace/outgoing! deps params))
  (let [{:keys [error values]} (transforms/cut-settings params)]
    (if error (current! deps {:error error :status 422 :draft params})
        (try (db/save-cut-defaults! (:store catalog) values)
             (when workspace (workspace/update-workspace! workspace :settings update :draft #(apply dissoc % (map first transforms/cut-fields))))
             (current! deps {:message "Saved mount-cut defaults."})
             (catch Exception e (current! deps {:error (str "Could not save defaults. " (ex-message e)) :status 422}))))))

(defn classification! [{:keys [catalog workspace] :as deps} action {:keys [params]}]
  (when workspace (workspace/outgoing! deps params))
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

(defn export-root! [{:keys [catalog library workspace] :as deps} {:keys [params]}]
  (when workspace (workspace/outgoing! deps params))
  (try
    (let [root (export-directory/prepare! (get params "export-root") (index/root! library))]
      (db/save-export-root! (:store catalog) root)
      (when workspace (workspace/update-workspace! workspace :settings update :draft dissoc "export-root"))
      (current! deps {:message "Saved export directory."}))
    (catch Exception e (current! deps {:error (ex-message e) :status 422 :draft params}))))

(defn variant! [{:keys [catalog workspace] :as deps} {:keys [params]}]
  (when workspace (workspace/outgoing! deps params))
  (try
    (db/move-variant! (:store catalog) (keyword (get params "variant")) (keyword (get params "direction")))
    (current! deps {:message "Saved default variant order."})
    (catch Exception e (current! deps {:error (ex-message e) :status 422}))))
