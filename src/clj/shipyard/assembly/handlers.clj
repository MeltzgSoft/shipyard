(ns shipyard.assembly.handlers
  "Thin Ring orchestration; transport validation is in routes."
  (:require [clojure.edn :as edn]
            [shipyard.assembly.db :as db]
            [shipyard.assembly.views :as views]
            [shipyard.loadout.operations :as loadouts]
            [shipyard.http.htmx :as htmx]))

(defn- response [result]
  (htmx/fragment
   ;; Matrices and mount data grow with the assembly and exceed Jetty's
   ;; response-header limit. Hiccup escapes the EDN in this inert body field;
   ;; the viewport consumes it once after HTMX swaps the response into #detail.
   (list (views/panel result)
         [:input {:type "hidden" :data-assembly-event (pr-str (:event result))}])
   {:status (:status result)}))

(defn current! [deps {:keys [params]}]
  (response (assoc (db/request! deps nil {:resume? (not= "1" (get params "poll"))
                                          :retry (get params "retry")})
                   :loadouts (loadouts/list! (:loadout-store deps))
                   :selected-hull (get params "part-id")
                   :selected-bundle (not-empty (get params "bundle"))
                   :selected-class (not-empty (get params "class")))))

(defn mutate! [deps op {:keys [parameters]}]
  (let [{:keys [revision slot part-id bundle class]} (:form parameters)]
    (response (assoc (db/request! deps (cond-> {:op op :revision (parse-long revision) :part-id part-id}
                                         slot (assoc :slot (edn/read-string slot))) {})
                     :selected-bundle (not-empty bundle)
                     :selected-class (not-empty class)))))

(defn loadout! [deps op {:keys [parameters]}]
  (let [{:keys [id name]} (:form parameters)
        result (case op
                 :save (db/save-loadout! deps name)
                 :load (db/load-loadout! deps (parse-uuid id))
                 :duplicate (db/duplicate-loadout! deps (parse-uuid id) name))]
    (response (assoc result :loadouts (loadouts/list! (:loadout-store deps))))))
