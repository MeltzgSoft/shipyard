(ns shipyard.store.scan-index
  "Derived scan entries share the application's store and transaction boundary.
  Each entry has its own normalized library-root/part-path identity."
  (:require [datalevin.core :as d]
            [shipyard.store.db :as store]))

(def ^:private entry-attributes
  {:mtime :scan/mtime :size :scan/size :mesh-key :scan/mesh-key
   :tris :scan/tris :escort-analysis :scan/escort-analysis})

(defn- entry-value [entity]
  (into {} (keep (fn [[key attr]]
                   (when (contains? entity attr) [key (get entity attr)])))
        entry-attributes))

(defn entries!
  "Materialize one library's derived entries; absent entries cost recomputation."
  [database root]
  (store/read! database
               (fn [db]
                 (into {} (map (fn [entity] [(:scan/part-id entity) (entry-value entity)]))
                       (store/entities db :scan/root (store/library-location root) '[*])))))

(defn- put-entry-tx [db root part-id entry]
  (let [key [root part-id]
        old (d/pull db '[*] [:scan/key key])
        value (into {} (keep (fn [[field attr]]
                               (when-some [v (get entry field)] [attr v])))
                    entry-attributes)]
    (conj (vec (for [attr (vals entry-attributes)
                     :when (and (contains? old attr) (not (contains? value attr)))]
                 [:db.fn/retractAttribute (:db/id old) attr]))
          (assoc value :scan/key key :scan/root root :scan/part-id part-id))))

(defn put-entry!
  "Replace one entry without rewriting another part's concurrent result."
  [database root part-id entry]
  (store/write! database
                (fn [conn]
                  (d/transact! conn (put-entry-tx @conn (store/library-location root) part-id entry)))))

(defn replace!
  "Commit a refreshed library index, including removed parts and stale fields."
  [database root entries]
  (let [root (store/library-location root)]
    (store/write! database
                  (fn [conn]
                    (let [db @conn
                          old (store/entities db :scan/root root '[*])
                          by-id (into {} (map (juxt :scan/part-id identity)) old)
                          removed (for [entity old :when (not (contains? entries (:scan/part-id entity)))]
                                    [:db/retractEntity (:db/id entity)])
                          updates (mapcat (fn [[part-id entry]]
                                            (when (or (not (contains? by-id part-id))
                                                      (not= entry (entry-value (get by-id part-id))))
                                              (put-entry-tx db root part-id entry)))
                                          entries)]
                      (d/transact! conn (vec (concat removed updates))))))))
