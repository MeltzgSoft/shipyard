(ns shipyard.assembly.routes
  "Malli-enforced form contracts for the local ephemeral draft."
  (:require [clojure.edn :as edn]
            [shipyard.assembly.handlers :as handlers]
            [shipyard.http.contracts :as contracts]))

(def revision-schema [:and string? [:re #"[0-9]{1,15}"]])
(def id-schema [:string {:min 1 :max 2048}])

(defn slot-string?
  "Accept only bounded non-root EDN paths; no custom readers or arbitrary tags."
  [value]
  (boolean
   (and (string? value) (<= (count value) 4096)
        (try
          (let [forms (edn/read-string (str "[" value "]"))
                path (first forms)]
            (and (= 1 (count forms)) (vector? path) (<= 1 (count path) 16)
                 (every? #(and (vector? %) (= 2 (count %))
                               (keyword? (first %)) (integer? (second %))
                               (<= 0 (second %) 255)) path)))
          (catch Exception _ false)))))

(defn routes [deps]
  [["/assembly/drawer" {:post {:handler (partial handlers/drawer! deps)
                               :parameters {:form [:map [:revision revision-schema] [:slot [:fn slot-string?]]
                                                   [:open [:enum "true" "false"]]]}
                               :responses contracts/html-responses}}]
   ["/assembly/save" {:post {:handler (partial handlers/save! deps)
                             :parameters {:form [:map [:revision revision-schema] [:name string?]]}
                             :responses contracts/html-responses}}]
   ["/assembly" {:get {:handler (partial handlers/current! deps)
                       :responses contracts/html-responses}}]
   ["/assembly/hull" {:post {:handler (partial handlers/mutate! deps :hull)
                             :parameters {:form [:map [:revision revision-schema] [:part-id id-schema]
                                                 [:name {:optional true} string?]
                                                 [:discard-revision {:optional true} revision-schema]
                                                 [:bundle {:optional true} string?] [:class {:optional true} string?]]}
                             :responses contracts/html-responses}}]
   ["/assembly/assign" {:post {:handler (partial handlers/mutate! deps :assign)
                               :parameters {:form [:map [:revision revision-schema] [:part-id id-schema]
                                                   [:slot [:fn slot-string?]]
                                                   [:bundle {:optional true} string?] [:class {:optional true} string?]]}
                               :responses contracts/html-responses}}]
   ["/assembly/clear" {:post {:handler (partial handlers/mutate! deps :clear)
                              :parameters {:form [:map [:revision revision-schema] [:slot [:fn slot-string?]]
                                                  [:bundle {:optional true} string?] [:class {:optional true} string?]]}
                              :responses contracts/html-responses}}]
   ["/assembly/reset" {:post {:handler (partial handlers/mutate! deps :reset)
                              :parameters {:form [:map [:revision revision-schema]]}
                              :responses contracts/html-responses}}]])
