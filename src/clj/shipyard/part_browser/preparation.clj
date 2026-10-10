(ns shipyard.part-browser.preparation
  "Source-guarded mesh preparation shared by part and variant previews."
  (:require [babashka.fs :as fs]
            [shipyard.http.jobs :as jobs]
            [shipyard.http.urls :as urls]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]))

(defn entry! [{:keys [library cache jobs]} part]
  (let [part-id (:part/id part)
        mesh-key (index/mesh-key! library part-id)
        cached? (and mesh-key (fs/regular-file? (cache/tier-file cache mesh-key 0)))
        source (index/fresh-source-file! library part-id)
        job (when (and source (not cached?)) (jobs/submit! jobs part-id source))]
    (cond
      (nil? source)
      {:part part :state :failed :message "The source mesh is unavailable or changed. Rescan the library."}

      cached?
      {:part part :state :ready :mesh-key mesh-key :mesh-url (urls/mesh-url mesh-key 0)}

      (= :failed (:state job))
      {:part part :state :failed :message "Could not prepare this part."}

      :else
      {:part part :state :preparing :message "Preparing…"})))

