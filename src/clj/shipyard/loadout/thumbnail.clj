(ns shipyard.loadout.thumbnail
  "Read-only assembled previews, independent of workspace drafts and viewport state."
  (:require [babashka.fs :as fs]
            [shipyard.assembly.transforms :as assembly]
            [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.catalog.db :as catalog]
            [shipyard.geom :as geom]
            [shipyard.http.htmx :as htmx]
            [shipyard.loadout.db :as loadouts]
            [shipyard.loadout.model :as model]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.job :as job]
            [shipyard.part-browser.thumbnail :as thumbnail]
            [shipyard.scheme.db :as schemes]
            [shipyard.scheme.material :as material]
            [shipyard.ship.db :as ships]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(defn appearance [part profile path mesh-key]
  (let [id (:part/id part) role (:part/role-hint part)
        regions (:part/paint-regions part)
        details (get-in profile [:scheme/details path])
        layers (when (#{:role :layer} (first (material/material-source profile path id role)))
                 (:scheme/layers profile))]
    {:material (material/resolve-material profile path id role)
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

(defn- read-mesh! [cache mesh-key exact?]
  (let [tiers (if exact? [0] (reverse (range (count (:lod-tiers cache)))))
        ^java.io.File file (first (filter fs/regular-file? (map #(cache/tier-file cache mesh-key %) tiers)))]
    (wire/decode (Files/readAllBytes (.toPath file)))))

(defn thumbnail! [{:keys [catalog loadouts named-ships schemes cache] :as deps} {:keys [path-params]}]
  (try
    (let [{:keys [kind id]} path-params
          id (parse-uuid id)
          ship (when (= kind "ship") (ships/record! named-ships id))
          record (loadouts/record! loadouts (if (= kind "ship") (:ship/class ship) id))]
      (if-not record
        (htmx/fragment [:span "No preview"])
        (let [part-ids (distinct (cons (:loadout/hull record) (vals (:loadout/slots record))))
              database (catalog/from-parts (keep #(-> (catalog/part-context! catalog %) :part) part-ids))
              placements (assembly/placements database (model/from-record record 0 :preview))
              scheme (schemes/record! schemes (if ship (:ship/scheme ship) (:loadout/scheme record)))
              profile (material/effective-profile (if ship (job/editor-record ship scheme) scheme))
              prepared (into {} (for [id (distinct (map :part-id (vals placements)))]
                                  [id (bulk/grid-entry! deps (catalog/part database id))]))]
          (cond
            (or (empty? placements) (some #(= :failed (:state %)) (vals prepared)))
            (htmx/fragment [:span "Preview unavailable"])

            (some #(= :preparing (:state %)) (vals prepared))
            (htmx/fragment [:span {:hx-get (str "/ship-thumbnails/" kind "/" id)
                                   :hx-trigger "load delay:600ms" :hx-target "closest .ship-thumbnail"} "…"])

            :else
            (let [instances (mapv (fn [[path {:keys [part-id] :as placement}]]
                                    (let [mesh-key (get-in prepared [part-id :mesh-key])
                                          appearance (appearance (catalog/part database part-id) profile path mesh-key)]
                                      (assoc placement :appearance appearance :mesh-key mesh-key
                                             :exact? (boolean (or (seq (:regions appearance)) (seq (:details appearance))))))) placements)
                  ;; Repeated mounts share decoded geometry for this request.
                  meshes (into {} (for [[key exact? :as k] (distinct (map (juxt :mesh-key :exact?) instances))]
                                    [k (read-mesh! cache key exact?)]))
                  mesh (assembled-mesh (map #(assoc % :mesh (meshes [(:mesh-key %) (:exact? %)])) instances))]
              (htmx/fragment [:img {:src (thumbnail/image! mesh nil) :width 128 :height 88
                                    :alt (str "Preview of " (or (:ship/name ship) (:loadout/name record)))}]))))))
    (catch Exception _ (htmx/fragment [:span "Preview unavailable"]))))
