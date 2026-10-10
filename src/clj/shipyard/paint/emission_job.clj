(ns shipyard.paint.emission-job
  "Source-bound surface preparation on the shared worker pool."
  (:require [shipyard.paint.emission :as emission]
            [shipyard.preparation :as preparation]))

(defn- source! [service mesh-key]
  (let [value (emission/source-moments (preparation/read-mesh! service mesh-key 0))]
    {:value value :size (* 400 (count value))}))

(defn build! [service mesh-key regions details]
  (let [source (preparation/cached-source! service [:emission-source 1 mesh-key] source! [service mesh-key])]
    {:value (emission/group-moments source mesh-key regions details)}))

(defn request! [service {:keys [part-id mesh-key regions details]}]
  (when (and service mesh-key)
    (let [details (when (= part-id (:part-id details)) details)]
      (select-keys
       (preparation/request! service {:key [:emission 1 mesh-key regions details]
                                      :part-id part-id :mesh-key mesh-key :run! build!
                                      :args [service mesh-key regions details]})
       [:state :resource :message]))))
