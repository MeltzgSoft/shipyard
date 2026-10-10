(ns shipyard.paint.preparation
  (:require [shipyard.paint.topology :as topology]
            [shipyard.preparation :as preparation]
            [shipyard.preparation.transforms :as transforms]))

(defn build! [service mesh-key]
  {:bytes (topology/encode (preparation/read-mesh! service mesh-key 0))
   :content-type "application/octet-stream"})

(defn topology! [{:keys [preparation]} {:keys [params]}]
  (let [part-id (get params "part-id") mesh-key (get params "mesh-key")
        result (preparation/request! preparation {:key [:paint-topology 1 mesh-key 0]
                                                  :part-id part-id :mesh-key mesh-key
                                                  :run! build! :args [preparation mesh-key]})]
    {:status 200 :headers {"content-type" "application/edn" "cache-control" "no-store"}
     :body (pr-str (transforms/envelope result))}))

(defn routes [deps]
  (when (:preparation deps)
    [["/paint/preparation/topology" {:get {:handler (partial topology! deps)
                                           :parameters {:query [:map [:part-id string?] [:mesh-key [:re #"[0-9a-f]{64}"]]]}}}]]))
