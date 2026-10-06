(ns shipyard.importer.db
  "Disposable import review and publication into the selected library."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.jobs :as jobs]
            [shipyard.importer.archive :as archive]
            [shipyard.importer.transforms :as t]
            [shipyard.library.index :as index]
            [shipyard.store.db :as store]
            [shipyard.jobs :as workers]
            [shipyard.thumbnail.cache :as thumbnails]
            [shipyard.thumbnail.part :as previews]))

(defn session! [{:keys [workspace]}]
  (get-in @(:state workspace) [:workspaces :browse :import]))

(defn effective! [deps]
  (if-let [session (when (:workspace deps) (session! deps))]
    (merge deps (select-keys session [:catalog :library :jobs :thumbnails]) {:import-session session :shared-catalog (:catalog deps)})
    deps))

(defn listing! [{:keys [catalog import-session]}]
  (let [database (catalog/listing! catalog)]
    (if-not import-session database
            (update database :parts
                    (fn [parts]
                      (into {} (map (fn [[id part]]
                                      (let [files (t/members @(:entries import-session) id)]
                                        [id (assoc part :import/source (str/join " • " (map #(str/join " → " (:chain %)) files))
                                                   :import/files (mapv #(select-keys % [:key :chain :variant]) files)
                                                   :import/conflict? (some #(> (count (set (map :sha %))) 1)
                                                                           (vals (group-by :variant files))))]))) parts))))))

(defn- library-state! [parts root entries previous]
  (let [groups (group-by :group (sort-by :key (vals entries)))
        parts (mapv (fn [part]
                      (assoc part :part/source (:variant (t/thumbnail-entry (get groups (:part/id part))))
                             :part/source-paths
                             (into {} (for [[variant files] (group-by :variant (get groups (:part/id part)))
                                            :when (= 1 (count (set (map :sha files))))]
                                        [variant (str (fs/relativize root (:file (first files))))])))) parts)
        file-parts (mapv (fn [{:keys [key variant]}]
                           {:part/id (t/file-preview-id key) :part/source variant}) (vals entries))
        sources (into (into {} (map (fn [{:keys [key file]}] [(t/file-preview-id key) file])) (vals entries))
                      (keep (fn [part]
                              (when-let [source (t/thumbnail-entry (get groups (:part/id part)))]
                                [(:part/id part) (:file source)]))) parts)
        stored (select-keys (:entries previous)
                            (for [[id file] sources :when (= file (get-in previous [:source-files id]))] id))
        stats (update-vals sources #(hash-map :mtime (fs/file-time->millis (fs/last-modified-time %)) :size (fs/size %)))]
    ;; File previews share the import's cancellation scope and index, but never
    ;; become catalog rows or participate in grouping/publication.
    {:root root :parts parts :entries (index/refresh (concat parts file-parts) stored stats) :source-files sources}))

(defn close! [{:keys [jobs thumbnails store directory]}]
  (when thumbnails (thumbnails/close! thumbnails))
  (when jobs (ig/halt-key! :shipyard.http/jobs jobs))
  (when store (store/close! store))
  (when directory (fs/delete-tree directory)))

(defn prepare! [{:keys [cache library jobs thumbnails]} path]
  (when-not (index/available?! library)
    (throw (ex-info "Choose an existing library folder before importing." {})))
  (let [directory (fs/create-temp-dir {:prefix "shipyard-import-"})
        opened (atom {:directory directory})]
    (try
      (let [{raw :entries :keys [skipped-empty-archives]} (archive/extract! path directory)
            _ (when (empty? raw) (throw (ex-info "This archive contains no STL files." {})))
            root (str (fs/create-dirs (fs/path directory "models")))
            entries (into {} (for [{:keys [key file] :as entry} raw
                                   :let [target (fs/file root key "unsupported.stl")]]
                               (do (fs/create-dirs (fs/parent target))
                                   (fs/move file target)
                                   [key (assoc entry :file target)])))
            entries (t/inferred-entries entries)
            parts (t/review-parts entries {} {})
            store (store/open! (fs/path directory "database"))
            _ (swap! opened assoc :store store)
            lib {:store store :state (atom (library-state! parts root entries {}))}
            cat (catalog/open! store (:parts @(:state lib)) root)
            mesh-jobs (ig/init-key :shipyard.http/jobs {:library lib :cache cache :workers (:workers jobs) :priority :bulk})
            _ (swap! opened assoc :jobs mesh-jobs)
            previews (when thumbnails (thumbnails/fork! thumbnails :bulk))
            _ (when previews (swap! opened assoc :thumbnails previews))
            session (merge @opened {:library lib :catalog cat :entries (atom entries)
                                    :skipped-empty-archives skipped-empty-archives
                                    :archive (str path) :target-root (index/root! library)})]
        (when previews
          (let [result (previews/batch! (assoc session :cache cache :import-session true) (mapv :part/id parts))]
            (when-not (:accepted? result)
              (throw (ex-info "Import preview queue is full. Finish pending work or increase the configured queue-size before retrying." result)))))
        session)
      (catch Exception e (close! @opened) (throw e)))))

(defn- apply-review!
  [{:keys [catalog entries jobs] {:keys [state] :as library} :library} {:keys [labels selected] updated :entries}]
  (locking state
    (let [parts (t/review-parts updated @entries labels)
          root (index/root! library)
          candidate (library-state! parts root updated @state)
          parts (:parts candidate)
          database (:store catalog)
          staged (store/write! database
                               (fn [conn]
                                 (let [cat (assoc catalog :store (assoc database :conn conn) :state (atom @(:state catalog)))]
                                   (catalog/reingest! cat parts root)
                                   ;; New, merged and revived rows inherit reviewed labels. A pose
                                   ;; survives only while its unsupported source remains the same.
                                   (doseq [part parts
                                           :let [ref [:part/key [(:library @(:state cat)) (:part/id part)]]]]
                                     (d/transact! conn
                                                  (mapv (fn [[attribute source]]
                                                          (if-some [value (get part source)]
                                                            [:db/add ref attribute value]
                                                            [:db.fn/retractAttribute ref attribute]))
                                                        [[:part/name-override :part/name]
                                                         [:part/bundle-override :part/bundle]
                                                         [:part/class-override :part/class]
                                                         [:part/role-override :part/role-hint]
                                                         [:part/orientation :part/orientation]])))
                                   @(:state cat))))]
      (reset! (:state catalog) staged)
      (reset! (:state library) candidate)
      (reset! entries updated)
      (jobs/clear! jobs)
      selected)))

(defn- reviewed-parts! [catalog]
  (into {} (map (juxt :part/id identity)) (catalog/browse (catalog/listing! catalog) {})))

(defn group! [{:keys [catalog entries] :as session} ids group-name]
  (apply-review! session (t/group-selection @entries (reviewed-parts! catalog) ids group-name)))

(defn split! [{:keys [catalog entries] :as session} id]
  (apply-review! session (t/split-group @entries (reviewed-parts! catalog) id)))

(defn assign-variant! [{:keys [catalog entries] :as session} file-id variant]
  (apply-review! session {:entries (t/assign-variant @entries file-id variant)
                          :labels (reviewed-parts! catalog)}))

(defn variants! [{:keys [catalog entries] :as session} ids variant]
  (when-not (t/variants variant)
    (throw (ex-info "Choose supported, unsupported or unsupported-pitted." {})))
  (let [parts (reviewed-parts! catalog)
        files (mapv #(t/members @entries %) ids)]
    (when (or (empty? ids) (some empty? files))
      (throw (ex-info "Select available imported parts." {})))
    (when (some #(> (count %) 1) files)
      (throw (ex-info "Choose each file's supported/unsupported variant in the grouped row." {})))
    (apply-review! session {:entries (reduce #(assoc-in %1 [(:key (first %2)) :variant] variant) @entries files)
                            :labels parts})))

(defn plan! [session]
  (t/plan (vals (reviewed-parts! (:catalog session))) @(:entries session)))

(defn- target! [root path]
  (let [target (fs/path root path)]
    ;; Reject symlink ancestors, including an existing target. No imported path
    ;; may redirect a write outside the chosen library.
    (loop [p target]
      (when (fs/sym-link? p) (throw (ex-info (str "Import destination is a symbolic link: " p) {})))
      (when (and (not= p (fs/path root)) (fs/parent p)) (recur (fs/parent p))))
    (when (fs/exists? target)
      (throw (ex-info (str "Destination already exists: " path ". Rename the imported part or cancel; existing files are never overwritten.") {})))
    target))

(defn- publish-metadata! [{:keys [catalog]} plan existing-ids]
  (store/write! (:store catalog)
                (fn [conn]
                  (let [library (:library @(:state catalog))]
                    (d/transact! conn
                                 (mapv (fn [[id files]]
                                         (let [part (:part (or (some #(when (= :unsupported (:variant %)) %) files) (first files)))]
                                           (cond-> {:db/id [:part/key [library id]]
                                                    :part/role-override (:part/role-hint part)}
                                             (:part/orientation part) (assoc :part/orientation (:part/orientation part)))))
                                       (apply dissoc (group-by :id plan) existing-ids)))))))

(defn- publish! [{:keys [library catalog] :as deps} root plan]
  (let [database (:store catalog) state (:state library)]
    (locking state
      (let [existing-ids (keys (reviewed-parts! catalog))
            {:keys [candidate staged]}
            (store/write! database
                          (fn [conn]
                            (let [transaction (assoc database :conn conn)
                                  candidate (index/prepare-root! (assoc library :store transaction) root)
                                  cat (catalog/open! transaction (:parts candidate) root)]
                              (publish-metadata! (assoc deps :catalog cat) plan existing-ids)
                              {:candidate candidate :staged @(:state cat)})))]
        (reset! state candidate)
        (reset! (:state catalog) staged)))))

(defn commit! [{:keys [library] :as deps} session]
  (when-not (and (index/available?! library) (= (:target-root session) (index/root! library)))
    (throw (ex-info "The library folder changed or is unavailable. Restore it or cancel and restart this import." {})))
  (let [root (index/root! library)
        plan (mapv #(assoc % :target (target! root (:path %))) (plan! session))
        moved (atom [])]
    (when-let [previews (:thumbnails session)] (thumbnails/close! previews))
    (workers/close! (get-in session [:jobs :scope]))
    (try
      (doseq [{:keys [file target] :as entry} plan]
        (fs/create-dirs (fs/parent target))
        (fs/move file target)
        (swap! moved conj entry))
      (publish! deps root plan)
      {:files (count plan) :parts (count (set (map :id plan)))}
      (catch Exception e
        (doseq [{:keys [file target]} (reverse @moved)]
          (fs/move target file))
        (workers/reopen! (get-in session [:jobs :scope]))
        (jobs/clear! (:jobs session))
        (when-let [previews (:thumbnails session)]
          (thumbnails/reopen! previews)
          (previews/batch! (assoc session :cache (:cache deps) :import-session true)
                           (mapv :part/id (index/parts! (:library session)))))
        (throw e)))))
