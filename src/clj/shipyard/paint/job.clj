(ns shipyard.paint.job
  "Ship-owned paint values. Editor profiles reuse material operations without
  giving a ship's custom paint the identity or persistence of a shared scheme."
  (:require [clojure.set :as set]
            [shipyard.scheme.transforms :as scheme]))

(def profile-keys
  {:paint/layers :scheme/layers
   :paint/instances :scheme/instances :paint/groups :scheme/groups :paint/details :scheme/details})

(defn profile [job]
  (set/rename-keys job profile-keys))

(defn from-profile [record]
  (set/rename-keys (select-keys record (vals profile-keys)) (set/map-invert profile-keys)))

(defn valid? [job]
  (and (map? job) (every? (set (keys profile-keys)) (keys job))
       (scheme/custom-paint? (profile job))))

(defn palette [record]
  (select-keys record [:scheme/id :scheme/name :scheme/layers]))

(defn editor-record [ship scheme]
  (assoc (profile (:ship/paint ship))
         :scheme/id (:ship/id ship) :scheme/name (:ship/name ship)
         :paint/ship-id (:ship/id ship) :scheme/base (palette scheme)))
