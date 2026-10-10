(ns shipyard.part-browser.variants
  "Canonical variant edits with shared-store transactions and compensating file moves."
  (:require [babashka.fs :as fs]
            [datalevin.core :as d]
            [shipyard.catalog.db :as catalog]
            [shipyard.http.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.part-browser.variant-plan :as plan]
            [shipyard.store.db :as store]
            [shipyard.store.scan-index :as scan-index]))

(def source-pattern
  [:db/id :source/path :source/variant :source/present? :source/size :source/mtime
   {:source/part [:db/id :part/id :part/uid] :source/origin-part [:part/id]}])

(defn files! [{:keys [store state]}]
  (store/read! store
               (fn [db]
                 (let [lib (:library @state)]
                   (vec (for [part (d/q '[:find [(pull ?p pattern) ...] :in $ ?lib pattern
                                          :where [?l :library/id ?lib] [?p :part/library ?l] [?p :part/present? true]]
                                        db lib [{:part/sources source-pattern}])
                              source (:part/sources part) :when (:source/present? source)]
                          {:key (str (:db/id source)) :eid (:db/id source)
                           :owner (get-in source [:source/part :part/id])
                           :owner-eid (get-in source [:source/part :db/id])
                           :origin (get-in source [:source/origin-part :part/id])
                           :path (:source/path source) :variant (:source/variant source)
                           :size (:source/size source) :mtime (:source/mtime source)}))))))

(defn file! [catalog key] (first (filter #(= key (:key %)) (files! catalog))))

(defn- protected-parts! [catalog]
  (let [parts (:parts (catalog/listing! catalog))
        referenced (store/read! (:store catalog)
                                #(set (d/q '[:find [?id ...] :in $ ?library :where
                                             [?lib :library/id ?library] [?p :part/library ?lib]
                                             (or [?e :slot/part ?p] [?e :loadout/hull ?p] [?e :target/part ?p])
                                             [?p :part/id ?id]] % (:library @(:state catalog)))))]
    (update-vals parts #(assoc % :protected? (boolean (or (referenced (:part/id %))
                                                          (:part/has-regions? %) (seq (get-in % [:part/mount-summary :sockets]))
                                                          (pos? (or (get-in % [:part/mount-summary :plugs]) 0))))))))

(defn- safe-path! [root path]
  (let [root (fs/normalize (fs/absolutize root)) target (fs/normalize (fs/path root path))]
    (when (or (fs/absolute? path) (not (fs/starts-with? target root)) (= root target))
      (throw (ex-info "The file path is outside the active library." {})))
    (loop [p target]
      (when (fs/sym-link? p) (throw (ex-info "Variant edits cannot follow symbolic links." {})))
      (when (not= p root) (recur (fs/parent p))))
    target))

(defn- fresh-file! [root entry]
  (let [source (safe-path! root (:path entry))]
    (when-not (and (fs/regular-file? source) (= (:size entry) (fs/size source))
                   (= (:mtime entry) (fs/file-time->millis (fs/last-modified-time source))))
      (throw (ex-info "A variant file changed. Rescan the library and retry." {})))))

(defn preview-source!
  "Register an exact library file for guarded preview preparation, never for authoring."
  [{:keys [catalog library jobs]} key]
  (let [state (:state library)]
    (locking state
      (when-let [entry (file! catalog key)]
        (let [root (index/root! library) source (fs/file (safe-path! root (:path entry)))
              id (str "library-file-" key) stamp (select-keys entry [:size :mtime])
              unchanged? (and (= source (get-in @state [:source-files id]))
                              (= stamp (select-keys (get-in @state [:entries id]) [:size :mtime])))]
          (fresh-file! root entry)
          (swap! state (fn [current]
                         (-> current
                             (assoc-in [:source-files id] source)
                             (assoc-in [:entries id] (merge stamp (when unchanged? (get-in current [:entries id])))))))
          (when-not unchanged? (jobs/forget! jobs id))
          {:id id :entry entry})))))

(defn- relocate! [root moves publish!]
  (let [sources (set (map #(str (safe-path! root (get-in % [:before :path]))) moves))
        stage (fs/path root (str ".shipyard-variants-" (random-uuid)))
        prepared (mapv (fn [i move]
                         (let [source (safe-path! root (get-in move [:before :path])) target (safe-path! root (:path move))]
                           (when-not (and (fs/regular-file? source)
                                          (= (get-in move [:before :size]) (fs/size source))
                                          (= (get-in move [:before :mtime]) (fs/file-time->millis (fs/last-modified-time source))))
                             (throw (ex-info "A variant file changed. Rescan the library and retry." {})))
                           (when (and (fs/exists? target) (not (sources (str target))))
                             (throw (ex-info "A destination file already exists. No files were overwritten." {})))
                           {:source source :target target :temp (fs/path stage (str i ".stl"))}))
                       (range) moves)
        staged (atom []) published (atom [])]
    (fs/create-dirs stage)
    (try
      (doseq [{:keys [source temp] :as entry} prepared]
        (fs/move source temp) (swap! staged conj entry))
      (doseq [{:keys [target temp] :as entry} prepared]
        (fs/create-dirs (fs/parent target)) (fs/move temp target) (swap! published conj entry))
      (publish!)
      (catch Throwable failure
        (doseq [{:keys [target temp]} (reverse @published)] (fs/move target temp))
        (doseq [{:keys [source temp]} (reverse @staged)] (fs/move temp source))
        (throw failure))
      ;; Keep any remaining staged bytes if compensation itself is interrupted.
      (finally (when (empty? (fs/list-dir stage)) (fs/delete stage))))))

(defn edit! [{:keys [library catalog jobs]} action params]
  (let [library-lock (:state library) store-lock (:lock (:store catalog))]
    (locking library-lock
      (locking store-lock
        (let [root (index/root! library)
              _ (when-not (index/available?! library) (throw (ex-info "Restore the active library folder first." {})))
              parts (protected-parts! catalog)
              files (files! catalog)
              {:keys [moves changed-source label selected checked-files]} (plan/plan parts files action params)
              database (:store catalog) lib (:library @(:state catalog))
              _ (doseq [entry checked-files] (fresh-file! root entry))
              result
              (relocate!
               root moves
               #(store/write! database
                              (fn [conn]
                                (let [transaction (assoc database :conn conn)]
                                ;; Release unique keys before swapping assignments; the existing
                                ;; source entity follows its bytes to its new owner and filename.
                                  (doseq [{:keys [eid before]} moves]
                                    (d/transact! conn [[:db.fn/retractAttribute eid :source/key]
                                                       [:db/retract (:owner-eid before) :part/sources eid]]))
                                  (doseq [{:keys [eid owner variant before origin]} moves]
                                    (let [ref (store/part-ref! conn lib owner)
                                          uid (:part/uid (d/pull @conn [:part/uid] ref))]
                                      (d/transact! conn [{:db/id eid :source/key [uid variant] :source/part ref :source/variant variant
                                                          :source/origin-part (store/part-ref! conn lib (or origin (:owner before)))}
                                                         {:db/id ref :part/sources [eid]}])))
                                  (doseq [id changed-source]
                                    (scan-index/put-entry! transaction root id {})
                                    (when (d/pull @conn [:db/id] [:part/key [lib id]])
                                      (d/transact! conn [[:db.fn/retractAttribute [:part/key [lib id]] :part/orientation]])))
                                  (let [candidate (index/prepare-root! (assoc library :store transaction) root)
                                        cat (catalog/open! transaction (:parts candidate) root)]
                                    (when label (d/transact! conn [{:db/id [:part/key [lib (:id label)]] :part/name-override (:name label)}]))
                                    (when (= action :split)
                                      (doseq [{:keys [owner]} moves :when (not (contains? parts owner))]
                                        (let [parent (get parts (:group params))]
                                          (d/transact! conn [(into {:db/id [:part/key [lib owner]]}
                                                                   (keep (fn [[target source]] (when-some [value (get parent source)] [target value])))
                                                                   [[:part/bundle-override :part/bundle] [:part/class-override :part/class]
                                                                    [:part/role-override :part/role-hint]])]))))
                                    (doseq [id (distinct (concat (map :owner moves) (map (fn [move] (get-in move [:before :owner])) moves)))]
                                      (let [ref [:part/key [lib id]] entity (d/pull @conn [:part/revision] ref)]
                                        (d/transact! conn [{:db/id ref :part/revision (inc (or (:part/revision entity) 0))}])))
                                    {:candidate candidate :staged @(:state cat)})))))]
          (reset! (:state library) (:candidate result))
          (reset! (:state catalog) (:staged result))
          (jobs/clear! jobs)
          (if (= action :group) [] (filterv #(catalog/part (catalog/listing! catalog) %) selected)))))))
