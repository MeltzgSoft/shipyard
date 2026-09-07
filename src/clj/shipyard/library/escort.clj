(ns shipyard.library.escort
  "On-demand geometry classification for escort-class parts.

  Normal library startup remains metadata-only. This namespace is called by the
  explicit probe/classifier path and caches derived measurements in the scan
  index once they have been paid for."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [integrant.core :as ig]
            [shipyard.library.index :as index]
            [shipyard.math :as math]
            [shipyard.mesh.stl :as stl]
            [shipyard.mesh.volume :as volume]
            [shipyard.report :as report]
            [shipyard.system :as system]))

(def ^:const bin-size-mm 0.5)
(def ^:const min-shared-profile-mm 20.0)
(def ^:const min-shared-profile-ratio 0.4)
(def ^:const absolute-cross-section-tolerance-mm 0.3)
(def ^:const relative-cross-section-tolerance 0.02)
(def ^:const volume-tolerance-ratio 0.02)

(defn- triangle-points [^floats positions t]
  (let [o (* 9 (long t))]
    [[(double (aget positions o))
      (double (aget positions (+ o 1)))
      (double (aget positions (+ o 2)))]
     [(double (aget positions (+ o 3)))
      (double (aget positions (+ o 4)))
      (double (aget positions (+ o 5)))]
     [(double (aget positions (+ o 6)))
      (double (aget positions (+ o 7)))
      (double (aget positions (+ o 8)))]]))

(defn- triangle-area [[a b c]]
  (/ (math/length (math/cross (math/subtract b a) (math/subtract c a))) 2.0))

(defn- axis-order [bbox-min bbox-max]
  (->> (map-indexed vector (mapv (fn [a b] (Math/abs (- (double b) (double a))))
                                 bbox-min bbox-max))
       (sort-by second >)
       (mapv first)))

(defn- normalize-profile [profile]
  (if-let [lo (first (sort (keys profile)))]
    (into (sorted-map)
          (map (fn [[bin area]] [(- bin lo) area]))
          profile)
    (sorted-map)))

(defn- axial-profile
  "Area-weighted 0.5 mm bins along the longest bounding-box axis."
  [{:keys [^floats positions triangle-count bbox-min bbox-max]}]
  (let [axis (first (axis-order bbox-min bbox-max))
        lo (double (bbox-min axis))]
    (normalize-profile
     (reduce
      (fn [profile t]
        (let [points (triangle-points positions t)
              coords (map #(nth % axis) points)
              first-bin (long (Math/floor (/ (- (apply min coords) lo) bin-size-mm)))
              last-bin (long (Math/floor (/ (- (apply max coords) lo) bin-size-mm)))
              bins (range first-bin (inc last-bin))
              area (/ (triangle-area points) (count bins))]
          (reduce #(update %1 %2 (fnil + 0.0) area) profile bins)))
      (sorted-map)
      (range triangle-count)))))

(defn- vertex-key [[x y z]]
  [(float x) (float y) (float z)])

(defn- component-from [vertex->tris ^floats positions start]
  (loop [queue [start]
         seen #{}]
    (if-let [t (first queue)]
      (if (contains? seen t)
        (recur (subvec (vec queue) 1) seen)
        (let [neighbours (->> (triangle-points positions t)
                              (mapcat #(get vertex->tris (vertex-key %))))]
          (recur (into (subvec (vec queue) 1) neighbours)
                 (conj seen t))))
      (vec (sort seen)))))

(defn- connected-components
  "Triangle components connected by shared vertex positions."
  [{:keys [^floats positions triangle-count]}]
  (let [vertex->tris (reduce
                      (fn [m t]
                        (reduce #(update %1 (vertex-key %2) (fnil conj #{}) t)
                                m
                                (triangle-points positions t)))
                      {}
                      (range triangle-count))]
    (loop [remaining (set (range triangle-count))
           components []]
      (if-let [start (first remaining)]
        (let [component (component-from vertex->tris positions start)]
          (recur (reduce disj remaining component)
                 (conj components component)))
        components))))

(defn- component-volume [^floats positions component]
  (Math/abs (volume/signed positions (count component))))

(defn- component-volumes [{:keys [^floats positions]} components]
  ;; Component triangles are copied into a compact soup so the volume routine
  ;; can stay simple and indexed from zero.
  (mapv (fn [component]
          (let [out (float-array (* 9 (count component)))]
            (doseq [[i t] (map-indexed vector component)]
              (System/arraycopy positions (* 9 (long t)) out (* 9 i) 9))
            (component-volume out component)))
        components))

(defn measure
  "Measure one parsed STL for sibling-relative escort classification."
  [{:keys [bbox-min bbox-max triangle-count] :as mesh}]
  (let [components (connected-components mesh)
        order (axis-order bbox-min bbox-max)
        extents (mapv (fn [axis] (Math/abs (- (double (bbox-max axis))
                                              (double (bbox-min axis)))))
                      order)]
    {:sorted-extents (vec (sort extents))
     :axial-axis (first order)
     :length (first extents)
     :cross-section (vec (rest extents))
     :volume (Math/abs (volume/signed (:positions mesh) triangle-count))
     :component-count (count components)
     :component-volumes (component-volumes mesh components)
     :profile (axial-profile mesh)}))

(defn- close-cross-section? [a b]
  (every? (fn [[x y]]
            (let [tol (max absolute-cross-section-tolerance-mm
                           (* relative-cross-section-tolerance (min (double x) (double y))))]
              (<= (Math/abs (- (double x) (double y))) tol)))
          (map vector (:cross-section a) (:cross-section b))))

(defn- profile-agreement-mm [a b]
  (let [pa (:profile a)
        pb (:profile b)]
    (* bin-size-mm (count (filter (fn [bin]
                                    (and (contains? pa bin) (contains? pb bin)))
                                  (set (concat (keys pa) (keys pb))))))))

(defn variant-family? [a b]
  (and (close-cross-section? a b)
       (let [shared (profile-agreement-mm a b)]
         (>= shared (max min-shared-profile-mm
                         (* min-shared-profile-ratio
                            (min (double (:length a)) (double (:length b)))))))))

(defn- anchor? [measurement siblings]
  (some #(>= (double (:volume %)) (* 2.0 (double (:volume measurement)))) siblings))

(defn- volume-close? [a b]
  (<= (Math/abs (- (double a) (double b)))
      (* volume-tolerance-ratio (max 1.0 (double b)))))

(defn- assembly? [measurement siblings]
  (let [component-volumes (sort (:component-volumes measurement))
        sibling-volumes (sort (map :volume siblings))]
    (and (> (:component-count measurement) 1)
         (= (count component-volumes) (count sibling-volumes))
         (every? true? (map volume-close? component-volumes sibling-volumes)))))

(defn classify-measurement [measurement siblings]
  (let [family? (boolean (some #(variant-family? measurement %) siblings))
        anchor? (boolean (anchor? measurement siblings))
        assembly? (assembly? measurement siblings)]
    (cond
      assembly?
      {:escort/classification :kitbash-assembly
       :escort/confidence :high
       :escort/evidence {:component-volumes (:component-volumes measurement)}}

      family?
      {:escort/classification :whole-ship
       :escort/confidence :high
       :escort/evidence {:profile-agreement-mm (apply max (map #(profile-agreement-mm measurement %) siblings))}}

      anchor?
      {:escort/classification :kitbash-component
       :escort/confidence :medium
       :escort/evidence {:anchor-volume-ratio (apply max (map #(/ (:volume %) (:volume measurement)) siblings))}}

      :else
      {:escort/classification :unresolved
       :escort/confidence :low
       :escort/evidence {:reason :no-sibling-geometry-match}})))

(defn apply-classification [part classification]
  (case (:escort/classification classification)
    :whole-ship (assoc part :part/role-hint :ship
                       :part/role-source :geometry
                       :part/role-evidence classification)
    (assoc part :part/role-evidence classification)))

(defn- escort-part? [part]
  (= "escort" (some-> (:part/class part) (str/lower-case))))

(defn classify-library
  "Classify measured escort parts without reading files or component state."
  [parts measured]
  (let [by-siblings (group-by (juxt :part/bundle :part/class)
                              (filter escort-part? parts))]
    (vec
     (for [[_ siblings] by-siblings
           :let [siblings (filter #(contains? measured (:part/id %)) siblings)]
           part siblings
           :let [id (:part/id part)
                 measurement (get measured id)
                 other-measurements (mapv #(get measured (:part/id %))
                                          (remove #(= id (:part/id %)) siblings))
                 classification (classify-measurement measurement other-measurements)]]
       {:part/id id
        :part/name (:part/name part)
        :measurement measurement
        :classification classification
        :part (apply-classification part classification)}))))

(defn- measure-file! [root {:part/keys [id source]}]
  (measure (stl/parse-file! (fs/file root id (index/name-of source)))))

(defn analyze-library!
  "Analyze renderable escort parts in an initialized library index component."
  [{:keys [state] :as library}]
  (let [{:keys [root parts]} @state
        measured (into {}
                       (keep (fn [{:part/keys [id source] :as part}]
                               (when (and (escort-part? part) source)
                                 [id (or (index/escort-analysis! library id)
                                         (index/record-escort-analysis!
                                          library id (measure-file! root part)))])))
                       parts)]
    (classify-library parts measured)))

(defn- parse-args [args]
  (reduce (fn [m [k v]]
            (case k
              "--out"  (assoc m :out v)
              "--root" (assoc m :root v)
              m))
          {:out "escort-classification.edn"}
          (partition 2 args)))

(defn -main [& args]
  (let [{:keys [out root]} (parse-args args)
        cfg (system/load-config!)
        root (or root (get-in cfg [:shipyard.library/index :root]))
        _ (when-not root
            (println "No library root. Pass --root, or set one in Shipyard first.")
            (System/exit 2))
        library (ig/init-key :shipyard.library/index {:root root})
        report (analyze-library! library)]
    (println "escort classifications:" (count report))
    (println "report written to" (str (report/write-report! out report)))
    (System/exit 0)))
