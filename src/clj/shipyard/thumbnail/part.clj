(ns shipyard.thumbnail.part
  "Source-guarded mesh and PNG preparation as one deferred preview job."
  (:require [babashka.fs :as fs]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as meshes]
            [shipyard.part-browser.thumbnail :as renderer]
            [shipyard.part-browser.transforms :as parts]
            [shipyard.part.orientation :as orientation]
            [shipyard.thumbnail.cache :as cache]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(defn- current! [{:keys [catalog library]} id source stamp]
  (when-not (index/current-source?! library id source)
    (throw (ex-info "Thumbnail source changed before publication." {})))
  (when-not (= stamp (:stamp (catalog/thumbnail-context! catalog id)))
    (throw (ex-info "Thumbnail appearance changed before publication." {}))))

(defn- prepare! [{:keys [catalog library cache] :as deps} id scale expected stamp]
  (current! deps id expected stamp)
  (let [{:keys [mesh-key tris]} (meshes/ensure! cache (:source expected))
        part (:part (catalog/thumbnail-context! catalog id stamp))
        regions (renderer/source-regions (:part/paint-regions part) mesh-key)
        tiers (if (seq (:faces regions)) [0] (reverse (range (count (:lod-tiers cache)))))
        file (first (filter fs/regular-file? (map #(meshes/tier-file cache mesh-key %) tiers)))
        pose (orientation/orientation-of (:part/orientation part))]
    (index/record-mesh-key! library id mesh-key tris expected)
    (current! deps id expected stamp)
    {:inputs {:mesh mesh-key :tier (str (fs/file-name file)) :pose pose
              :regions (renderer/region-style regions) :size scale}
     :render! (fn []
                (current! deps id expected stamp)
                (let [png (renderer/png! (renderer/region-mesh (wire/decode (Files/readAllBytes (fs/path file))) regions) pose scale)]
                  (current! deps id expected stamp)
                  png))}))

(defn- plan! [{:keys [catalog library cache import-session] :as deps} id scale]
  (let [part (catalog/summary! catalog id)]
    (when (parts/thumbnail? part import-session)
      (when-let [source (index/fresh-source-file! library id)]
        (let [expected (-> (index/part-state! library id)
                           (update :entry select-keys [:mtime :size])
                           (assoc :source source))
              stamp (:stamp (catalog/thumbnail-context! catalog id))]
          {:stamp {:part stamp :source {:root (:root expected) :file (str source)
                                        :entry (select-keys (:entry expected) [:size :mtime])}
                   :tiers (:lod-tiers cache) :size scale}
           :prepare! #(prepare! deps id scale expected stamp)})))))

(defn request! [{:keys [thumbnails] :as deps} id scale retry?]
  (if-let [{:keys [stamp prepare!]} (plan! deps id scale)]
    (do (when retry? (cache/retry-derived! thumbnails stamp))
        (cache/request-derived! thumbnails stamp prepare!))
    {:state :unavailable}))

(defn batch!
  "Admit every eligible review row once, including rows not loaded in the browser."
  [{:keys [thumbnails] :as deps} ids]
  (let [requests (into [] (keep #(plan! deps % 1)) ids)]
    (assoc (cache/request-batch! thumbnails requests) :eligible (count requests))))

(defn file-request!
  "One accepted source preview prepares its mesh and PNG without another HTTP request."
  [{:keys [library cache thumbnails]} id retry?]
  (if-let [source (index/fresh-source-file! library id)]
    (let [expected (-> (index/part-state! library id)
                       (update :entry select-keys [:mtime :size])
                       (assoc :source source))
          stamp {:file (str source) :entry (select-keys (:entry expected) [:mtime :size])
                 :tiers (:lod-tiers cache)}
          guard! #(when-not (index/current-source?! library id expected)
                    (throw (ex-info "Preview file changed before publication." {})))]
      (when retry? (cache/retry-derived! thumbnails stamp))
      (cache/request-derived!
       thumbnails stamp
       #(do (guard!)
            (let [{:keys [mesh-key tris]} (meshes/ensure! cache source)
                  file (first (filter fs/regular-file?
                                      (map (fn [tier] (meshes/tier-file cache mesh-key tier))
                                           (reverse (range (count (:lod-tiers cache)))))))]
              (index/record-mesh-key! library id mesh-key tris expected)
              (guard!)
              {:inputs {:mesh mesh-key :tier (str (fs/file-name file))
                        :pose orientation/identity-quaternion :regions nil}
               :render! (fn []
                          (guard!)
                          (let [png (renderer/png! (wire/decode (Files/readAllBytes (fs/path file)))
                                                   orientation/identity-quaternion)]
                            (guard!) png))}))))
    {:state :unavailable}))
