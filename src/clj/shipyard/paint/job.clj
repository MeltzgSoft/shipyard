(ns shipyard.paint.job
  "Ship-owned paint values. Editor profiles reuse material operations without
  giving a ship's custom paint the identity or persistence of a shared scheme."
  (:require [clojure.set :as set]))

(def profile-keys
  {:paint/details :scheme/details})

(defn profile [job]
  (set/rename-keys (select-keys job (keys profile-keys)) profile-keys))

(defn from-profile [record]
  (set/rename-keys (select-keys record (vals profile-keys)) (set/map-invert profile-keys)))

(defn palette [record]
  (select-keys record [:scheme/id :scheme/name :scheme/layers]))

(defn editor-record [ship scheme]
  (assoc (profile (:ship/paint ship))
         :scheme/id (:ship/id ship) :scheme/name (:ship/name ship)
         :paint/ship-id (:ship/id ship) :scheme/base (palette scheme)))
