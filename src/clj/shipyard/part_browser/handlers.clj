(ns shipyard.part-browser.handlers
  (:require [babashka.fs :as fs]
            [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.urls :as urls]
            [shipyard.mesh.cache :as cache]
            [shipyard.part-browser.thumbnail :as thumbnail]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(defn thumbnail! [{:keys [catalog cache] :as deps} {:keys [path-params]}]
  (let [id (:id path-params)
        part (catalog/summary! catalog id)]
    (if-not (:part/renderable part)
      (htmx/fragment [:span "No preview"])
      (let [{:keys [state mesh-key]} (bulk/grid-entry! deps part)]
        (case state
          :ready
          (try
            (let [part (:part (catalog/part-context! catalog id))
                  regions (thumbnail/source-regions (:part/paint-regions part) mesh-key)
                  ;; Face identities refer to the original mesh, never a simplified LOD.
                  tiers (if (seq (:faces regions)) [0] (reverse (range (count (:lod-tiers cache)))))
                  ^java.io.File file (first (filter fs/regular-file? (map #(cache/tier-file cache mesh-key %) tiers)))
                  mesh (-> (wire/decode (Files/readAllBytes (.toPath file)))
                           (thumbnail/region-mesh regions))]
              (htmx/fragment [:img {:src (thumbnail/image! mesh (:part/orientation part))
                                    :width 128 :height 88 :alt (str "Preview of " (:part/name part))}]))
            (catch Exception _ (htmx/fragment [:span "Preview unavailable"])))
          :preparing (htmx/fragment [:span {:hx-get (str "/thumbnails/" (urls/encode-id id))
                                            :hx-trigger "load delay:600ms" :hx-target "closest .part-thumbnail"} "…"])
          (htmx/fragment [:span "Preview unavailable"]))))))
