(ns shipyard.part-browser.handlers
  (:require [babashka.fs :as fs]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.urls :as urls]
            [shipyard.library.index :as index]
            [shipyard.part-browser.variants :as variants]
            [shipyard.importer.db :as importer]
            [shipyard.importer.transforms :as imports]
            [shipyard.part-browser.transforms :as t]
            [shipyard.thumbnail.part :as part-preview]
            [shipyard.thumbnail.views :as preview-views]))

(defn- thumbnail-effective! [{:keys [catalog] :as deps} {:keys [path-params params]}]
  (let [id (:id path-params) scale (if (= "large" (get params "size")) 2 1)
        url (str "/thumbnails/" (urls/encode-id id) (when (= 2 scale) "?size=large"))
        part (catalog/summary! catalog id)]
    (if-not (t/thumbnail? part (:import-session deps))
      (htmx/fragment [:span "No preview"])
      (try
        (htmx/fragment (preview-views/preview (part-preview/request! deps id scale (= "1" (get params "retry"))) url
                                              "closest .part-thumbnail" (:part/name part)))
        (catch Exception _ (htmx/fragment [:span "Preview unavailable"]))))))

(defn thumbnail! [deps request]
  (thumbnail-effective! (importer/effective! deps) request))

(defn file-thumbnail!
  "Preview one original import file, independent of its group or assigned variant."
  [deps {:keys [path-params params]}]
  (let [{:keys [import-session] :as deps} (importer/effective! deps)
        key (:file path-params) entry (when import-session (get @(:entries import-session) key))]
    (htmx/fragment
     (preview-views/preview
      (if entry (part-preview/file-request! deps (imports/file-preview-id key) (= "1" (get params "retry")))
          {:state :unavailable})
      (str "/imports/thumbnails/" key) "closest .import-file-thumbnail" (last (:chain entry))))))

(defn library-file-thumbnail! [{:keys [catalog library] :as deps} {:keys [path-params params]}]
  (let [key (:file path-params) entry (variants/file! catalog key)
        id (str "library-file-" key) url (str "/parts/variants/thumbnails/" key)]
    (if-not entry
      (htmx/fragment [:span "Preview unavailable"])
      (let [state (:state library)]
        (locking state
          (let [source (fs/file (index/root! library) (:path entry))
                stamp (select-keys entry [:size :mtime])]
            (swap! state (fn [current]
                           (-> current
                               (assoc-in [:source-files id] source)
                               (assoc-in [:entries id] (merge stamp (when (and (= source (get-in current [:source-files id]))
                                                                               (= stamp (select-keys (get-in current [:entries id]) [:size :mtime])))
                                                                      (get-in current [:entries id])))))))))
        (htmx/fragment
         (preview-views/preview (part-preview/file-request! deps id (= "1" (get params "retry")))
                                url "closest .import-file-thumbnail" (:path entry)))))))
