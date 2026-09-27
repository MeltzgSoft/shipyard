(ns shipyard.paint.job
  "Ship-owned paint values. Editor profiles reuse material operations without
  giving a ship's custom paint the identity or persistence of a shared scheme."
  (:require [clojure.set :as set]
            [shipyard.scheme.transforms :as scheme]))

(def profile-keys
  {:paint/roles :scheme/roles :paint/layers :scheme/layers
   :paint/instances :scheme/instances :paint/groups :scheme/groups :paint/details :scheme/details})

(defn profile [job]
  (merge {:scheme/roles {}} (set/rename-keys job profile-keys)))

(defn from-profile [record]
  (let [value (set/rename-keys (select-keys record (vals profile-keys)) (set/map-invert profile-keys))]
    (cond-> value (empty? (:paint/roles value)) (dissoc :paint/roles))))

(defn valid? [job]
  (and (map? job) (every? (set (keys profile-keys)) (keys job))
       (scheme/scheme? (assoc (profile job) :scheme/id #uuid "00000000-0000-0000-0000-000000000000"
                              :scheme/name "Ship paint" :scheme/layer-ids? true))))

(defn palette [record]
  (select-keys record [:scheme/id :scheme/name :scheme/layers :scheme/layer-ids?]))

(defn editor-record [ship scheme]
  (assoc (profile (:ship/paint ship))
         :scheme/id (:ship/id ship) :scheme/name (:ship/name ship)
         :paint/ship-id (:ship/id ship) :scheme/base (palette scheme)))

(defn legacy [record]
  (from-profile (select-keys record [:scheme/roles :scheme/instances :scheme/groups :scheme/details])))
