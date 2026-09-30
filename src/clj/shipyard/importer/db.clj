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
            [shipyard.store.db :as store]))

(defn session! [{:keys [workspace]}]
  (get-in @(:state workspace) [:workspaces :browse :import]))

(defn effective! [deps]
  (if-let [session (when (:workspace deps) (session! deps))]
    (merge deps (select-keys session [:catalog :library :jobs]) {:import-session session})
    deps))

(defn listing! [{:keys [catalog import-session]}]
  (let [database (catalog/listing! catalog)]
    (if-not import-session database
            (update database :parts
                    (fn [parts]
                      (into {} (map (fn [[id part]]
                                      (let [entry (get @(:entries import-session) id)]
                                        [id (assoc part :import/source (str/join " → " (:chain entry))
                                                   :import/variant (:variant entry))]))) parts))))))

(defn close! [{:keys [jobs store directory]}]
  (when jobs (ig/halt-key! :shipyard.http/jobs jobs))
  (when store (store/close! store))
  (when directory (fs/delete-tree directory)))

(defn prepare! [{:keys [cache library]} path]
  (when-not (index/available?! library)
    (throw (ex-info "Choose an existing library folder before importing." {})))
  (let [directory (fs/create-temp-dir {:prefix "shipyard-import-"})
        opened (atom {:directory directory})]
    (try
      (let [raw (archive/extract! path directory)
            _ (when (empty? raw) (throw (ex-info "This archive contains no STL files." {})))
            root (str (fs/create-dirs (fs/path directory "models")))
            entries (into {} (for [{:keys [key file] :as entry} raw
                                   :let [target (fs/file root key "unsupported.stl")]]
                               (do (fs/create-dirs (fs/parent target))
                                   (fs/move file target)
                                   [key (assoc entry :file target)])))
            parts (mapv (fn [[id {:keys [chain]}]] (t/infer id chain)) entries)
            store (store/open! (fs/path directory "database"))
            _ (swap! opened assoc :store store)
            lib {:store store :state (atom {:root root :parts parts
                                            :entries (index/refresh! parts root {})
                                            :source-files (into {} (for [part parts :when (:part/renderable part)]
                                                                     [(:part/id part) (:file (get entries (:part/id part)))]))})}
            cat (catalog/open! store parts root)
            workers (ig/init-key :shipyard.http/jobs {:library lib :cache cache})]
        (merge @opened {:library lib :catalog cat :jobs workers :entries (atom entries)
                        :archive (str path) :target-root (index/root! library)}))
      (catch Exception e (close! @opened) (throw e)))))

(defn variants! [{:keys [library catalog entries jobs]} ids variant]
  (when-not (#{:supported :unsupported :unsupported-pitted} variant)
    (throw (ex-info "Choose supported, unsupported or unsupported-pitted." {})))
  (when (or (empty? ids) (some #(not (contains? @entries %)) ids))
    (throw (ex-info "Select available imported parts." {})))
  (swap! entries #(reduce (fn [m id] (assoc-in m [id :variant] variant)) % ids))
  (let [selected (set ids)
        parts (mapv (fn [part]
                      (if (selected (:part/id part))
                        (assoc part :part/variants #{variant} :part/renderable (= variant :unsupported)
                               :part/source (when (= variant :unsupported) :unsupported)) part)) (index/parts! library))
        root (index/root! library)
        state (:state library)]
    (locking state
      (reset! (:state library) {:root root :parts parts :entries (index/refresh! parts root {})
                                :source-files (into {} (for [part parts :when (:part/renderable part)]
                                                         [(:part/id part) (:file (get @entries (:part/id part)))]))}))
    (jobs/clear! jobs)
    (catalog/reingest! catalog parts root)))

(defn plan! [session]
  (t/plan (vals (:parts (catalog/listing! (:catalog session)))) @(:entries session)))

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
      (let [existing-ids (keys (:parts (catalog/listing! catalog)))
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
        (throw e)))))
