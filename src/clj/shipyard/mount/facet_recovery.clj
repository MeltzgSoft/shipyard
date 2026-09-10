(ns shipyard.mount.facet-recovery
  "Recover mesh-scoped facet selections saved by pre-M3 mount authoring."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.catalog.db :as db]
            [shipyard.catalog.part :as catalog-part]
            [shipyard.http.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.mesh.facet :as facet]
            [shipyard.wire :as wire])
  (:import [java.io ByteArrayOutputStream FileInputStream]))

(defn retained-facet? [mesh-key mount]
  (and (= mesh-key (get-in mount [:mount/facet :mesh-key]))
       (seq (get-in mount [:mount/facet :indices]))))

(defn legacy-mounts [mesh-key mounts]
  (remove #(retained-facet? mesh-key %) mounts))

(defn- same-mount-frame? [a b]
  (= (select-keys a [:mount/id :mount/pos :mount/axis :mount/roll])
     (select-keys b [:mount/id :mount/pos :mount/axis :mount/roll])))

(defn matches
  "Purely derive recoverable facet indices. The caller owns scheduling and I/O."
  [mesh mounts]
  (into {}
        (keep (fn [mount]
                (when-let [indices (seq (facet/match-frame mesh mount))]
                  [(:mount/id mount) {:mount mount :indices (vec indices)}])))
        mounts))

(defn apply-matches
  "Apply recoveries only when the durable mount has not changed since scheduling."
  [mesh-key mounts matched]
  (mapv (fn [mount]
          (if-let [{saved :mount indices :indices} (get matched (:mount/id mount))]
            (if (and (not (retained-facet? mesh-key mount))
                     (same-mount-frame? saved mount))
              (assoc mount :mount/facet {:mesh-key mesh-key :indices indices})
              mount)
            mount))
        mounts))

(defn- read-bytes! [f]
  (with-open [in (FileInputStream. (fs/file f))
              out (ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn backfill!
  "Perform one recovery in the already-bounded job pool."
  [{:keys [catalog library cache]} part-id mesh-key mounts]
  (let [mesh (wire/decode (read-bytes! (cache/tier-file cache mesh-key 0)))
        matched (matches mesh mounts)]
    (when (and (seq matched) (= mesh-key (index/mesh-key! library part-id)))
      (let [current (catalog-part/durable-mounts
                     (:part/mounts (db/part (db/snapshot! catalog) part-id)))
            updated (apply-matches mesh-key current matched)]
        (when (not= current updated)
          (db/save-mounts! catalog part-id updated))))))

(defn recover!
  "Submit one mesh-key-scoped recovery when this detail response needs it."
  [{:keys [jobs] :as deps} part mesh-key]
  (let [mounts (catalog-part/durable-mounts (:part/mounts part))]
    (when (seq (legacy-mounts mesh-key mounts))
      (jobs/submit-facet-backfill!
       jobs [(:part/id part) mesh-key]
       #(backfill! deps (:part/id part) mesh-key mounts)))))
