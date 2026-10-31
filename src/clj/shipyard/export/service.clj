(ns shipyard.export.service
  "Lifecycle-owned export and preview work on the shared bounded worker pool."
  (:require [babashka.fs :as fs]
            [integrant.core :as ig]
            [shipyard.export.db :as db]
            [shipyard.export.geometry :as geometry]
            [shipyard.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.topology :as topology]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(defn- preview! [{:keys [catalog library cache exports] :as deps} options part-id]
  (let [library-lock (:state library) store-lock (:lock (:store catalog))]
    (locking library-lock
      (locking store-lock
        (let [{:keys [files database]} (db/snapshot! deps (:selection options) (:priority options))
              entry (or (some #(when (= part-id (:part-id %)) %) files)
                        (throw (ex-info "Choose a part in the current export inventory." {})))
              source (db/source-path! (index/root! library) entry)
              bytes (geometry/bytes! database (:parsed-diameter options) entry source)
              temporary (fs/path (:directory exports) (str (random-uuid) ".stl"))]
          (try
            (Files/write temporary ^bytes bytes ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
            (let [key (:mesh-key (cache/ensure! cache (fs/file temporary)))
                  mesh (wire/decode (Files/readAllBytes (fs/path (cache/tier-file cache key 0))))]
              (db/source-path! (index/root! library) entry)
              {:mesh-key key :part-id part-id :topology (topology/encode mesh)})
            (finally (Files/deleteIfExists temporary))))))))

(defn- execute! [{:keys [exports library] :as deps} id options preview]
  (let [result (try
                 (when-not (= (:root options) (index/root! library))
                   (throw (ex-info "The library changed. Reopen export controls." {})))
                 (if preview
                   (assoc (preview! deps options preview) :state :ready :kind :preview)
                   {:state :ready :kind :export
                    :path (db/export! deps (:selection options) (:priority options) (:directory options)
                                      (fn [{:keys [database]} entry source]
                                        (geometry/bytes! database (:parsed-diameter options) entry source)))})
                 (catch Exception e {:state :failed :message (ex-message e)}))]
    (swap! (:state exports) #(if (= id (:id %)) (merge % result) %))))

(defn request! [{:keys [exports library] :as deps} options preview]
  (let [state (:state exports)]
    (locking state
      (when (= :running (:state @state))
        (throw (ex-info "An export or preview is already running. Wait for it to finish." {})))
      (let [id (str (random-uuid))
            options (assoc options :root (index/root! library))]
        (reset! state {:id id :state :running :options options})
        (when-not (:accepted? (jobs/submit-batch! (:scope exports)
                                                  [{:key id :run! execute! :args [deps id options preview]}]))
          (swap! state assoc :state :failed :message "Export capacity is busy. Try again shortly."))))))

(defmethod ig/init-key :shipyard.export.service/jobs [_ {:keys [workers]}]
  {:scope (jobs/scope! workers) :state (atom {}) :directory (fs/create-temp-dir {:prefix "shipyard-export-preview-"})})

(defmethod ig/halt-key! :shipyard.export.service/jobs [_ {:keys [scope directory]}]
  (jobs/close! scope)
  (fs/delete-tree directory))
