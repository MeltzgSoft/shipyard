(ns shipyard.proof.m2-human-navy-cruiser
  "On-demand M2 proof over the real Human Navy Cruiser.

  The Cruiser STL files are proprietary user data, so this namespace records the
  exact authoring recipe without committing the models. Run it against a
  temporary Shipyard-style copy of the Cruiser folders; it writes `shipyard.edn`
  sidecars beside the parts and then proves a fresh catalog can reload them."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [integrant.core :as ig]
            [shipyard.assembly.model :as assembly]
            [shipyard.catalog.db :as db]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.geom :as geom]
            [shipyard.library.index :as index]
            [shipyard.library.scan :as scan]
            [shipyard.math :as math]
            [shipyard.mesh.facet :as facet]
            [shipyard.mesh.stl :as stl]
            [shipyard.proof.geometry :as proof-geometry]
            [shipyard.report :as report]))

(def parts
  {:hull "Human Navy Fleet Bundle/Cruiser/Hull"
   :prow "Human Navy Fleet Bundle/Cruiser/Classic Ram Prow"
   :bridge "Human Navy Fleet Bundle/Cruiser/Bridge"
   :antenna "Human Navy Fleet Bundle/Cruiser/Antenna 1"
   :lance "Human Navy Fleet Bundle/Cruiser/weapons/Lance Battery"
   :weapon "Human Navy Fleet Bundle/Cruiser/weapons/Weapon Battery"
   :lance-turret "Human Navy Fleet Bundle/Cruiser/weapons/turrets/Lance Turret"
   :dorsal-turret "Human Navy Fleet Bundle/Cruiser/weapons/turrets/Dorsal Turret"})

(defn mount
  ([id kind accepts pos axis roll origin]
   (mount id kind accepts pos axis roll origin 1))
  ([id kind accepts pos axis roll origin capacity]
   (cond-> {:mount/id id
            :mount/kind kind
            :mount/pos pos
            :mount/axis axis
            :mount/roll roll
            :mount/origin origin}
     accepts (assoc :mount/accepts accepts
                    :mount/capacity capacity))))

(def authoring
  {(:hull parts)
   {:part-role :hull
    :mounts [(mount :prow :socket #{:prow} [0.0 0.0 86.76601] [0.0 0.0 1.0] [1.0 0.0 0.0] :picked)
             (mount :bridge :socket #{:bridge} [0.0 18.87 52.0] [0.0 1.0 0.0] [1.0 0.0 0.0] :picked)
             (mount :antenna :socket #{:antenna} [0.0 18.87 46.0] [0.0 1.0 0.0] [1.0 0.0 0.0] :picked)
             (mount :port-1 :socket #{:weapon} [-19.061 0.0 48.0] [-1.0 0.0 0.0] [0.0 0.0 1.0] :picked 2)
             (mount :starboard-1 :socket #{:weapon} [19.061 0.0 48.0] [1.0 0.0 0.0] [0.0 0.0 1.0] :mirrored 2)
             (mount :turret-1 :socket #{:turret} [0.0 18.87 38.0] [0.0 1.0 0.0] [1.0 0.0 0.0] :picked)
             (mount :turret-2 :socket #{:turret} [0.0 18.87 58.0] [0.0 1.0 0.0] [1.0 0.0 0.0] :picked)]}

   (:prow parts)
   {:part-role :prow
    :mounts [(mount :plug :plug nil [412.94 7.69 0.0] [0.0 0.0 -1.0] [1.0 0.0 0.0] :picked)]}

   (:bridge parts)
   {:part-role :bridge
    :mounts [(mount :plug :plug nil [-0.38 -0.05 5.0] [0.0 0.0 -1.0] [1.0 0.0 0.0] :picked)]}

   (:antenna parts)
   {:part-role :antenna
    :mounts [(mount :plug :plug nil [386.71 28.29 0.0] [0.0 0.0 -1.0] [1.0 0.0 0.0] :picked)]}

   (:weapon parts)
   {:part-role :weapon
    :mounts [(mount :plug :plug nil [230.0 29.31 2.13] [-1.0 0.0 0.0] [0.0 0.0 1.0] :picked)
             (mount :turret-pit :socket #{:turret} [238.53 29.31 4.263] [0.0 0.0 1.0] [1.0 0.0 0.0] :picked)]}

   (:lance parts)
   {:part-role :weapon
    :mounts [(mount :plug :plug nil [155.0 29.31 4.46] [-1.0 0.0 0.0] [0.0 0.0 1.0] :picked)]}

   (:lance-turret parts)
   {:part-role :turret
    :mounts [(mount :plug :plug nil [257.0 27.64 0.0] [0.0 0.0 -1.0] [1.0 0.0 0.0] :picked)]}

   (:dorsal-turret parts)
   {:part-role :turret
    :mounts [(mount :plug :plug nil [267.0 26.47 0.0] [0.0 0.0 -1.0] [1.0 0.0 0.0] :picked)]}})

(defn- nanos->ms [n]
  (/ (double n) 1000000.0))

(defn- round3 [n]
  (/ (Math/round (* 1000.0 (double n))) 1000.0))

(defn- mesh-file [root scanned-by-id part-id]
  (let [{:part/keys [source]} (get scanned-by-id part-id)]
    (when-not source
      (throw (ex-info "required Cruiser part has no renderable STL"
                      {:part-id part-id})))
    (fs/file root part-id (index/name-of source))))

(defn- bbox! [root scanned-by-id part-id]
  (let [m (stl/parse-file! (mesh-file root scanned-by-id part-id))]
    {:triangles (:triangle-count m)
     :bbox-min (:bbox-min m)
     :bbox-max (:bbox-max m)}))

(defn- dominant-axis-index [axis]
  (->> (map-indexed (fn [i x] [i (Math/abs (double x))]) axis)
       (apply max-key second)
       first))

(defn- bbox-face-span [bbox axis]
  (let [drop-index (dominant-axis-index axis)
        extents (mapv (fn [a b] (round3 (- (double b) (double a))))
                      (:bbox-min bbox) (:bbox-max bbox))]
    (vec (keep-indexed #(when (not= drop-index %1) %2) extents))))

(defn- verify-parts! [scanned]
  (let [found (set (map :part/id scanned))
        missing (vec (remove found (vals parts)))]
    (when (seq missing)
      (throw (ex-info "missing required Human Navy Cruiser parts"
                      {:missing missing
                       :expected-layout parts}))))
  scanned)

(defn- library! [root cache-home]
  (ig/init-key :shipyard.library/index {:root root :cache-home cache-home}))

(defn- catalog! [library]
  (ig/init-key :shipyard.catalog/db {:library library}))

(defn- save-authoring-with-times! [catalog]
  (into {}
        (map (fn [[part-id authoring]]
               (let [started (System/nanoTime)]
                 (db/save-authoring! catalog part-id authoring)
                 [part-id (nanos->ms (- (System/nanoTime) started))])))
        authoring))

(defn- mount-summary [bbox mount]
  (assoc (select-keys mount [:mount/id :mount/kind :mount/accepts
                             :mount/capacity :mount/pos :mount/axis :mount/roll
                             :mount/origin])
         :bbox-face-span-mm (bbox-face-span bbox (:mount/axis mount))))

(defn- distance3 [a b]
  (math/length (math/subtract a b)))

(defn- source-surface-diagnostics
  "Mount origins must land on an actual source surface and their axes must be
  normal to it. This catches stale coordinates even though attachment math can
  still make two stale frames agree algebraically."
  [root scanned-by-id]
  (vec
   (mapcat
    (fn [[part-id {:keys [mounts]}]]
      (let [mesh (stl/parse-file! (mesh-file root scanned-by-id part-id))]
        (keep (fn [{:mount/keys [id pos axis]}]
                (let [{:keys [triangle distance-mm normal]} (proof-geometry/nearest-surface mesh pos)
                      axis-dot (when normal (math/dot axis normal))]
                  (cond
                    (> distance-mm 0.1)
                    {:code :mount-off-surface :part-id part-id :mount-id id
                     :nearest-triangle triangle :distance-mm (round3 distance-mm)
                     :remedy "Pick the intended mating face again in Mount authoring."}

                    (< (Math/abs (double axis-dot)) 0.98)
                    {:code :mount-axis-not-normal :part-id part-id :mount-id id
                     :nearest-triangle triangle :axis-dot (round3 axis-dot)
                     :remedy "Pick the intended mating face again; do not use a guessed axis."})))
              mounts)))
    authoring)))

(defn- transform-vector [matrix vector]
  (math/subtract (geom/transform-point matrix vector)
                 (geom/transform-point matrix [0.0 0.0 0.0])))

(defn- attachment-check [parent socket child]
  (let [plug (first (filter #(= :plug (:mount/kind %)) (:mounts child)))
        check {:parent-part-id parent :mount-id (:mount/id socket) :child-part-id (:part/id child)}]
    (try
      (let [matrix (geom/attachment-matrix geom/identity-matrix socket plug)
            anchor-error (distance3 (:mount/pos socket)
                                    (geom/transform-point matrix (:mount/pos plug)))
            axis-dot (math/dot (:mount/axis socket)
                               (transform-vector matrix (:mount/axis plug)))
            roll-dot (math/dot (:mount/roll socket)
                               (transform-vector matrix (:mount/roll plug)))
            up-dot (math/dot (math/cross (:mount/axis socket) (:mount/roll socket))
                             (transform-vector matrix
                                               (math/cross (:mount/axis plug)
                                                           (:mount/roll plug))))]
        (assoc check
               :anchor-error-mm (round3 anchor-error) :axis-dot (round3 axis-dot)
               :roll-dot (round3 roll-dot) :up-dot (round3 up-dot)
               :status (if (and (<= anchor-error 1e-6)
                                (<= axis-dot -0.999999)
                                (<= roll-dot -0.999999)
                                (>= up-dot 0.999999))
                         :pass :transform-mismatch)))
      (catch clojure.lang.ExceptionInfo e
        (assoc check :status :transform-mismatch :error-code (:code (ex-data e)))))))

(defn- m3-assembly-audit [root scanned-by-id catalog]
  (let [database (db/snapshot! catalog)
        hull-id (:hull parts)
        derived (assembly/slots database hull-id {})
        authored-parts (into {} (map (fn [[id facts]] [id (assoc facts :part/id id)])) authoring)
        hull (get authored-parts hull-id)
        by-role (group-by :part-role (vals authored-parts))
        pairs (for [socket (:mounts hull)
                    :let [role (first (:mount/accepts socket))]
                    child (sort-by :part/id (get by-role role))]
                (attachment-check hull-id socket child))
        transform-errors (filter #(= :transform-mismatch (:status %)) pairs)
        geometry-errors (source-surface-diagnostics root scanned-by-id)
        diagnostics (vec (concat (:errors derived) geometry-errors
                                 (map #(assoc % :code :transform-mismatch) transform-errors)))]
    {:status (if (seq diagnostics) :blocked :ready)
     :slots (mapv :id (:slots derived))
     :diagnostics diagnostics
     :attachment-checks (vec pairs)
     :note "A blocked proof is intentional: it names the exact legacy mount that must be reauthored, instead of fabricating a geometry placement."}))

(defn- part-summary! [root scanned-by-id reloaded timings [part-id {:keys [mounts part-role]}]]
  (let [bbox (bbox! root scanned-by-id part-id)
        reloaded-part (db/part (db/snapshot! reloaded) part-id)
        elapsed-ms (get timings part-id)
        mount-count (count mounts)]
    [part-id {:part-role part-role
              :mount-count mount-count
              :mounts (mapv #(mount-summary bbox %) mounts)
              :author-ms (round3 elapsed-ms)
              :ms-per-mount (round3 (/ elapsed-ms (max 1 mount-count)))
              :reloaded-role (:part/role-hint reloaded-part)
              :reloaded-mount-count (count (:part/mounts reloaded-part))
              :sidecar-version (:shipyard/version (sidecar/read-sidecar! root part-id))}]))

(defn- totals []
  (let [mounts (mapcat :mounts (vals authoring))]
    {:parts (count authoring)
     :mounts (count mounts)
     :plugs (count (filter #(= :plug (:mount/kind %)) mounts))
     :sockets (count (filter #(= :socket (:mount/kind %)) mounts))
     :socket-capacity (reduce + 0 (map #(long (or (:mount/capacity %) 1))
                                       (filter #(= :socket (:mount/kind %)) mounts)))
     :mirrored-sockets (count (filter #(= :mirrored (:mount/origin %)) mounts))
     :turret-sockets (count (filter #(contains? (:mount/accepts %) :turret) mounts))}))

(defn run-proof!
  "Write proof sidecars into `root` and return the report map."
  [{:keys [root cache-home notes]
    :or {cache-home (fs/file (System/getProperty "java.io.tmpdir") "shipyard-m2-human-navy-cache")}}]
  (when-not root
    (throw (ex-info "pass --root pointing at a temporary Cruiser library copy" {})))
  (let [root (str root)
        scanned (verify-parts! (vec (scan/scan! (fs/file root))))
        scanned-by-id (into {} (map (juxt :part/id identity)) scanned)
        lib (library! root cache-home)
        cat (catalog! lib)
        timings (save-authoring-with-times! cat)
        reloaded (catalog! (library! root cache-home))
        m3-assembly (m3-assembly-audit root scanned-by-id reloaded)]
    {:root root
     :ran-at (str (java.time.Instant/now))
     :parts (into (sorted-map)
                  (map (fn [[k id]] [k (merge {:part/id id} (bbox! root scanned-by-id id))]))
                  parts)
     :facet-tolerances facet/default-options
     :authored (into (sorted-map)
                     (map (partial part-summary! root scanned-by-id reloaded timings))
                     authoring)
     :totals (totals)
     :m3-assembly m3-assembly
     :ambiguous-roll-cases []
     :notes (vec (concat ["Run against a temporary Shipyard-style copy of Human Navy/HN Cruiser.zip."
                          "Sidecars were written through shipyard.catalog.db/save-authoring! and reloaded through a fresh catalog."
                          "The :m3-assembly audit checks legacy authoring before attempting a live Cruiser assembly."]
                         notes))}))

(defn- parse-args [args]
  (reduce (fn [m [k v]]
            (case k
              "--root" (assoc m :root v)
              "--out" (assoc m :out v)
              "--cache-home" (assoc m :cache-home v)
              "--note" (update m :notes (fnil conj []) v)
              m))
          {:out "m2-human-navy-cruiser-proof.edn"}
          (partition 2 args)))

(defn -main [& args]
  (let [{:keys [out] :as opts} (parse-args args)
        report (run-proof! opts)]
    (pp/pprint report)
    (println "report written to" (str (report/write-report! out report)))
    (System/exit 0)))
