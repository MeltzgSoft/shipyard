(ns shipyard.loadout.thumbnail
  "Read-only assembled previews, independent of workspace drafts and viewport state."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [shipyard.assembly.transforms :as assembly]
            [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.catalog.db :as catalog]
            [shipyard.geom :as geom]
            [shipyard.http.htmx :as htmx]
            [shipyard.loadout.model :as model]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.job :as job]
            [shipyard.part-browser.thumbnail :as thumbnail]
            [shipyard.scheme.material :as material]
            [shipyard.thumbnail.cache :as previews]
            [shipyard.thumbnail.db :as sources]
            [shipyard.thumbnail.views :as preview-views]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(defn appearance [part profile path mesh-key]
  (let [id (:part/id part)
        regions (:part/paint-regions part)
        details (get-in profile [:scheme/details path])
        layers (:scheme/layers profile)]
    {:material (material/resolve-material profile)
     :regions (when (and (seq layers) (= mesh-key (:mesh-key regions))) (:faces regions))
     :layers layers
     :details (when (and (= id (:part-id details)) (= mesh-key (:mesh-key details))) (:faces details))}))

(defn assembled-mesh
  "Apply world transforms and source-bound paint before fitting the whole assembly."
  [instances]
  (reduce
   (fn [result {:keys [mesh matrix appearance]}]
     (let [vertices (mapv vec (partition 3 (:positions mesh)))
           offset (quot (count (:positions result)) 3)
           {:keys [material regions layers details]} appearance
           colors (mapv (fn [ids]
                          (let [key (when (or (seq regions) (seq details)) (faces/face-key (mapv vertices ids)))
                                inherited (or (get layers (get regions key)) material)]
                            (:base (faces/resolve-material inherited (get details key)))))
                        (partition 3 (:indices mesh)))]
       (-> result
           (update :positions into (mapcat #(geom/transform-point matrix %) vertices))
           (update :indices into (map #(+ offset %) (:indices mesh)))
           (update :colors into colors))))
   {:positions [] :indices [] :colors []} instances))

(defn- mesh-file! [cache mesh-key exact?]
  (let [tiers (if exact? [0] (reverse (range (count (:lod-tiers cache)))))
        ^java.io.File file (first (filter fs/regular-file? (map #(cache/tier-file cache mesh-key %) tiers)))]
    file))

(defn- prepare-preview! [deps kind id stamp mesh-keys]
  (let [{:keys [database record ship scheme]} (sources/assembly-context! deps kind id stamp)
        placements (assembly/placements database (model/from-record record 0 :preview))
        profile (material/effective-profile (if ship (job/editor-record ship scheme) scheme))
        instances (mapv (fn [[path {:keys [part-id] :as placement}]]
                          (let [mesh-key (get mesh-keys part-id)
                                appearance (appearance (catalog/part database part-id) profile path mesh-key)]
                            (assoc placement :appearance appearance :mesh-key mesh-key
                                   :exact? (boolean (or (seq (:regions appearance)) (seq (:details appearance)))))))
                        (sort-by (comp pr-str key) placements))
        files (into {} (for [[key exact? :as k] (distinct (map (juxt :mesh-key :exact?) instances))]
                         [k (mesh-file! (:cache deps) key exact?)]))
        inputs (mapv (fn [instance]
                       (assoc (select-keys instance [:matrix :appearance :mesh-key])
                              :tier (str (fs/file-name (files [(:mesh-key instance) (:exact? instance)]))))) instances)]
    {:inputs {:assembly inputs}
     :render! #(let [meshes (update-vals files (fn [^java.io.File file]
                                                 (wire/decode (Files/readAllBytes (.toPath file)))))
                     mesh (assembled-mesh (map (fn [instance]
                                                 (assoc instance :mesh (meshes [(:mesh-key instance) (:exact? instance)]))) instances))]
                 (thumbnail/png! mesh nil))}))

(defn thumbnail! [{:keys [cache thumbnails] :as deps} {:keys [path-params]}]
  (try
    (let [{:keys [kind id]} path-params
          id (parse-uuid id)
          context (sources/assembly-context! deps kind id)]
      (if-not context
        (htmx/fragment [:span "No preview"])
        (let [{:keys [database record stamp label]} context
              placements (assembly/placements database (model/from-record record 0 :preview))
              prepared (into {} (for [id (distinct (map :part-id (vals placements)))]
                                  [id (bulk/grid-entry! deps (catalog/part database id))]))]
          (cond
            (or (empty? placements) (some #(= :failed (:state %)) (vals prepared)))
            (htmx/fragment [:span "Preview unavailable"])

            (some #(= :preparing (:state %)) (vals prepared))
            (htmx/fragment [:span {:hx-get (str "/ship-thumbnails/" kind "/" id)
                                   :hx-trigger "load delay:600ms" :hx-target "closest .ship-thumbnail"} "…"])

            :else
            (let [mesh-keys (update-vals prepared :mesh-key)
                  result (previews/request-derived!
                          thumbnails {:assembly stamp :meshes mesh-keys :tiers (:lod-tiers cache)}
                          #(prepare-preview! deps kind id stamp mesh-keys))]
              (htmx/fragment (preview-views/preview result (str "/ship-thumbnails/" kind "/" id)
                                                    "closest .ship-thumbnail" label)))))))
    (catch Exception e
      (log/warn e "Could not prepare ship thumbnail")
      (htmx/fragment [:span "Preview unavailable"]))))
