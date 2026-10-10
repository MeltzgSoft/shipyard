(ns shipyard.thumbnail.transforms
  "Stable identities for derived previews, independent of map insertion order."
  (:require [digest]))

(def renderer-version 2)

(defn- canonical [value]
  (cond
    (map? value) [:map (mapv (fn [[k v]] [(canonical k) (canonical v)]) (sort-by (comp pr-str key) value))]
    (set? value) [:set (vec (sort-by pr-str (map canonical value)))]
    (sequential? value) [:seq (mapv canonical value)]
    :else value))

(defn cache-key [inputs]
  (binding [*print-length* nil *print-level* nil *print-meta* false *print-dup* false]
    (digest/sha-256 (pr-str [renderer-version (canonical inputs)]))))
