(ns shipyard.part-browser.handlers
  (:require [babashka.fs :as fs]
            [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.urls :as urls]
            [shipyard.mesh.cache :as cache]
            [shipyard.importer.db :as importer]
            [shipyard.importer.transforms :as imports]
            [shipyard.part-browser.thumbnail :as thumbnail]
            [shipyard.part-browser.transforms :as t]
            [shipyard.part.orientation :as orientation]
            [shipyard.thumbnail.cache :as previews]
            [shipyard.thumbnail.views :as preview-views]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(defn- thumbnail-effective! [{:keys [catalog cache thumbnails] :as deps} {:keys [path-params params]}]
  (let [id (:id path-params)
        scale (if (= "large" (get params "size")) 2 1)
        url (str "/thumbnails/" (urls/encode-id id) (when (= 2 scale) "?size=large"))
        part (catalog/summary! catalog id)]
    (if-not (t/thumbnail? part (:import-session deps))
      (htmx/fragment [:span "No preview"])
      (let [{:keys [state mesh-key]} (bulk/grid-entry! deps part)]
        (case state
          :ready
          (try
            (let [stamp (:stamp (catalog/thumbnail-context! catalog id))
                  result (previews/request-derived!
                          thumbnails {:part stamp :mesh mesh-key :tiers (:lod-tiers cache) :size scale}
                          #(let [part (:part (catalog/thumbnail-context! catalog id stamp))
                                 regions (thumbnail/source-regions (:part/paint-regions part) mesh-key)
                                 ;; Face identities require the original mesh.
                                 tiers (if (seq (:faces regions)) [0] (reverse (range (count (:lod-tiers cache)))))
                                 ^java.io.File file (first (filter fs/regular-file? (map (fn [tier] (cache/tier-file cache mesh-key tier)) tiers)))
                                 pose (orientation/orientation-of (:part/orientation part))]
                             {:inputs {:mesh mesh-key :tier (str (fs/file-name file)) :pose pose
                                       :regions (thumbnail/region-style regions) :size scale}
                              :render! (fn [] (thumbnail/png! (-> (wire/decode (Files/readAllBytes (.toPath file)))
                                                                  (thumbnail/region-mesh regions)) pose scale))}))]
              (htmx/fragment (preview-views/preview result url
                                                    "closest .part-thumbnail" (:part/name part))))
            (catch Exception _ (htmx/fragment [:span "Preview unavailable"])))
          :preparing (htmx/fragment [:span {:hx-get url
                                            :hx-trigger "load delay:600ms" :hx-target "closest .part-thumbnail"} "…"])
          (htmx/fragment [:span "Preview unavailable"]))))))

(defn thumbnail! [deps request]
  (thumbnail-effective! (importer/effective! deps) request))

(defn file-thumbnail!
  "Preview one original import file, independent of its group or assigned variant."
  [deps {:keys [path-params]}]
  (let [{:keys [import-session cache thumbnails] :as deps} (importer/effective! deps)
        key (:file path-params)
        entry (when import-session (get @(:entries import-session) key))
        url (str "/imports/thumbnails/" key)
        target "closest .import-file-thumbnail"]
    (if-not entry
      (htmx/fragment [:span "Preview unavailable"])
      (let [{:keys [state mesh-key]} (bulk/grid-entry! deps {:part/id (imports/file-preview-id key)})]
        (case state
          :ready
          (let [^java.io.File file (first (filter fs/regular-file?
                                                  (map #(cache/tier-file cache mesh-key %)
                                                       (reverse (range (count (:lod-tiers cache)))))))
                pose orientation/identity-quaternion
                result (previews/request! thumbnails
                                          {:mesh mesh-key :tier (str (fs/file-name file)) :pose pose :regions nil}
                                          #(thumbnail/png! (wire/decode (Files/readAllBytes (.toPath file))) pose))]
            (htmx/fragment (preview-views/preview result url target (last (:chain entry)))))
          :preparing (htmx/fragment [:span {:hx-get url :hx-trigger "load delay:600ms" :hx-target target} "…"])
          (htmx/fragment [:span "Preview unavailable"]))))))
