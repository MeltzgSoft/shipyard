(ns shipyard.assembly.handlers
  "Thin Ring orchestration; transport validation is in routes."
  (:require [clojure.edn :as edn]
            [shipyard.assembly.db :as db]
            [shipyard.assembly.responses :as responses]
            [shipyard.http.htmx :as htmx]))

(defn- response [result]
  (htmx/fragment
   [:section#assembly {:data-draft (pr-str (:draft result))}
    [:h2 "Assembly"]
    (when (:error result) [:p {:role "alert"} (get responses/messages (:error result) (name (:error result)))])
    [:pre (pr-str (:draft result))]]
   {:status (:status result) :events {:assembly (:event result)}}))

(defn current! [deps {:keys [params]}]
  (response (db/request! deps nil {:resume? (not= "1" (get params "poll"))
                                   :retry (get params "retry")})))

(defn mutate! [deps op {:keys [parameters]}]
  (let [{:keys [revision slot part-id]} (:form parameters)]
    (response (db/request! deps (cond-> {:op op :revision (parse-long revision) :part-id part-id}
                                  slot (assoc :slot (edn/read-string slot))) {}))))
