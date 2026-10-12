(ns shipyard.fleet.transforms
  "Pure fleet-list validation and targeted identity decisions."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def default-variant-preference [:unsupported :supported :unsupported-pitted])
(def variant-preference? (m/validator schemas/variant-preference))
(def fleet? (m/validator schemas/fleet))

(defn put-record [exists? record mode]
  (cond
    (not (fleet? record)) {:error :invalid-fleet}
    (not (#{:create :update} mode)) {:error :invalid-operation}
    (and (= :create mode) exists?) {:error :id-exists}
    (and (= :update mode) (not exists?)) {:error :missing-fleet}
    :else {:fleet record}))

(defn delete-record [exists? id]
  (cond
    (not (uuid? id)) {:error :invalid-fleet-id}
    (not exists?) {:error :missing-fleet}
    :else {:deleted id}))

(defn source-path
  "Project native source separators to portable ZIP/manifest paths."
  [path separator]
  (str/replace path (str separator) "/"))
