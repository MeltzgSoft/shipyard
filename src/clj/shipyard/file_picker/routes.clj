(ns shipyard.file-picker.routes
  (:require [shipyard.file-picker.db :as db]
            [shipyard.file-picker.views :as views]
            [shipyard.http.contracts :as contracts]
            [shipyard.http.htmx :as htmx]))

(defn- choose! [{:keys [file-picker]} {{{:keys [field]} :path} :parameters}]
  (try
    (if-let [path (db/choose! file-picker (:kind (get views/fields field)))]
      (htmx/fragment (views/selected field path))
      {:status 204 :headers {} :body ""})
    (catch Exception e
      (htmx/fragment [:span.detail__error (ex-message e)]))))

(defn routes [deps]
  [["/files/choose/:field" {:post {:handler (partial choose! deps)
                                   :parameters {:path [:map [:field [:enum "settings-root" "setup-root"]]]}
                                   :responses contracts/html-responses}}]])
