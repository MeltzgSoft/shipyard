(ns shipyard.regions.migration
  "Pure, deterministic upgrade of name-based region and scheme references."
  (:require [shipyard.regions.model :as model])
  (:import [java.util UUID]
           [java.nio.charset StandardCharsets]))

(defn legacy-id [name]
  (if (some #{name} model/builtins) name
      (str "layer:" (UUID/nameUUIDFromBytes (.getBytes (str "shipyard.paint.layer/" name) StandardCharsets/UTF_8)))))

(defn regions [value]
  (when value
    (if (= 2 (:version value)) value
        (let [ids (into {} (map (juxt identity legacy-id)) (:layers value))]
          (-> value
              (assoc :version 2 :layer-definitions (into {} (for [[name id] ids :when (not (some #{id} model/builtins))]
                                                              [id {:name name :preview-name name}])))
              (update :layers #(mapv ids %))
              (update :faces #(into {} (map (fn [[face name]] [face (get ids name)])) %)))))))

(defn scheme [record]
  (if (or (:scheme/layer-ids? record) (not (contains? record :scheme/layers))) record
      (-> record
          (assoc :scheme/layer-ids? true)
          (update :scheme/layers #(into {} (map (fn [[name material]] [(legacy-id name) material])) %)))))
