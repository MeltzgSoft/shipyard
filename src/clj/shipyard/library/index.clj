(ns shipyard.library.index
  "Library root plus the mtime+size scan cache (TECHNICAL.md §5.4).

  Two keys, two purposes. `:part/id` is the folder path, free during the walk.
  `:part/mesh-key` is the SHA-256 of the source STL and is computed only when a
  part is first preprocessed - hashing 19 GB at every start is exactly what this
  namespace exists to prevent."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.library.scan :as scan]
            [shipyard.system :as system]))

(defn index-file
  "`cache-home` is injectable so a test can be hermetic: an E2E run scanning a
  fixture tree must not write part ids from a temp directory into the index the
  developer's real library depends on."
  ([] (index-file (system/cache-home)))
  ([cache-home] (fs/file cache-home "shipyard" "index.edn")))

(defn load-index
  "The stored entries, but only if they were scanned from `root`.

  **The stamp is not decoration.** Entries are keyed by library-relative part
  id, which identified a part uniquely only while there was one root. Now that
  the root is a setting (issue #35), two libraries can each hold
  `Cruiser/Hull`, and serving one's cached mesh key for the other would hand
  the viewport a mesh of the wrong ship. A mismatch costs a rescan; the
  alternative costs correctness."
  [f root]
  (if (fs/regular-file? f)
    (try
      (let [stored (edn/read-string (slurp f))]
        (if (= (str root) (:root stored))
          (:entries stored)
          {}))
      (catch Exception e
        ;; Derived data: a corrupt index costs a rescan, never correctness.
        (log/warn "discarding unreadable scan index:" (ex-message e))
        {}))
    {}))

(defn save-index! [f root entries]
  (system/write-atomically! f (pr-str {:root (str root) :entries entries})))

(defn- stat [f] {:mtime (fs/file-time->millis (fs/last-modified-time f)) :size (fs/size f)})

(defn fresh?
  "An entry survives only while its source file's mtime and size both match.
  Re-pitting a hull changes both, so the cache self-invalidates."
  [entry source]
  (and entry (= (select-keys entry [:mtime :size]) (stat source))))

(defn name-of [variant]
  (case variant
    :unsupported-pitted "unsupported-pitted.stl"
    :unsupported        "unsupported.stl"
    :supported          "supported.stl"))

(defn refresh
  "Merge a fresh scan against the stored index, carrying forward `:mesh-key` for
  parts whose source file is unchanged and dropping it for everything else."
  [parts root stored]
  (reduce
   (fn [acc {:part/keys [id source]}]
     (if-not source
       (assoc acc id (dissoc (get stored id) :mesh-key))   ; nothing renderable
       (let [f   (fs/file root id (name-of source))
             old (get stored id)]
         (assoc acc id
                (merge (stat f)
                       (when (fresh? old f)
                         (select-keys old [:mesh-key :tris])))))))
   {}
   parts))

(defn record-mesh-key!
  "Remember the mesh key a preprocess produced, and persist the index.

  This is the half of §5.4 that `refresh` was already written for and nothing
  yet fed: it carries `:mesh-key` forward for any part whose source file is
  unchanged, so a restart can name a part's mesh URL without re-hashing the
  file. Writing the whole map each time is cheap next to what it saves - the
  alternative is a SHA-256 over a 20 MB STL on every part you open.

  A part the current library does not contain is dropped rather than recorded.
  A preprocess job outlives the root that started it - the pool is still
  running when the settings form points the library somewhere else - and
  writing its result into the new library's index would file a mesh key under
  another library's part."
  [{:keys [state]} part-id mesh-key tris]
  (let [updated (swap! state
                       (fn [{:keys [entries] :as st}]
                         (if (contains? entries part-id)
                           (update-in st [:entries part-id]
                                      (fn [entry]
                                        (cond-> (assoc entry :mesh-key mesh-key)
                                          tris (assoc :tris tris))))
                           st)))]
    (when (contains? (:entries updated) part-id)
      (save-index! (:index-file updated) (:root updated) (:entries updated)))
    mesh-key))

(defn mesh-key
  "The recorded mesh key for a part, or nil if it has never been preprocessed
  or its source file has changed since."
  [{:keys [state]} part-id]
  (get-in @state [:entries part-id :mesh-key]))

;; --- the component, and the root it can be pointed at ------------------------

(defn root
  "Where the library currently is, or nil when nobody has said yet."
  [{:keys [state]}] (:root @state))

(defn available?
  "Whether the root is a directory that exists **now**. False is the normal
  state of a fresh install, not an error.

  Checked rather than remembered, and the difference is a stat per library
  request. A drive can be unmounted, or a folder renamed, under a running
  server; a remembered answer keeps listing that library's parts, and every row
  in the list 404s when clicked. Reporting the missing folder is both true and
  the only thing the user can act on."
  [{:keys [state]}]
  (let [{:keys [root]} @state]
    (boolean (and root (fs/directory? (fs/file root))))))

(defn parts [{:keys [state]}] (:parts @state))

(defn- scan-state
  "Scan `root` and build the component's whole value. Pure enough to be the one
  place that knows what a library's state consists of, so starting and
  relocating cannot drift apart."
  [root cache-home]
  (let [f (index-file cache-home)]
    (if (str/blank? (str root))
      ;; Nothing set. Not a failure - the settings form exists for exactly this
      ;; state, and there is nothing to scan or stamp until it is used.
      {:root nil :parts [] :entries {} :index-file f}
      (let [dir (fs/file root)]
        (when-not (fs/directory? dir)
          ;; Not fatal: the app must still start so the user can point it
          ;; somewhere real. A hard failure here makes a fresh install unusable.
          (log/warn "library root does not exist:" root))
        (let [stored (load-index f root)
              parts  (or (scan/scan dir) [])
              idx    (refresh parts root stored)]
          (log/infof "library: %d parts, %d with a cached mesh key"
                     (count parts) (count (filter :mesh-key (vals idx))))
          (when-not (= idx stored) (save-index! f root idx))
          {:root (str root) :parts parts :entries idx :index-file f})))))

(defn set-root!
  "Point the library at `root` and rescan, in place.

  In place, rather than by rebuilding the component, because the route table
  closes over its dependencies at build time (`shipyard.http.routes/routes`).
  Swapping this atom is what lets a running server serve a different library
  without a restart; rebuilding the component would leave every handler holding
  the old one."
  [{:keys [cache-home state]} root]
  (reset! state (scan-state root cache-home)))

(defmethod ig/init-key :shipyard.library/index [_ {:keys [root cache-home]}]
  (let [cache-home (or cache-home (system/cache-home))]
    {:cache-home cache-home
     :state      (atom (scan-state root cache-home))}))
