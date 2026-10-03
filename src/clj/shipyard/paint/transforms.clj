(ns shipyard.paint.transforms
  "Pure brush instance identities and material input parsing."
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.loadout.model :as loadout]
            [shipyard.scheme.transforms :as scheme]))

(defn targets [database draft]
  (mapv (fn [[path id]]
          (let [part (catalog/part database id)]
            {:key (pr-str path) :path path :part-id id
             :label (str (or (:part/name part) id) " · " (if (empty? path) "Hull" (pr-str path)))}))
        (loadout/part-tree draft)))

(defn color-hex [base]
  (apply str "#" (map #(format "%02x" (Math/round (* 255.0 %))) base)))

(defn parse-material [{:strs [base metalness roughness glow paint] :as params}]
  (try
    (when (and (string? base) (re-matches #"#[0-9a-fA-F]{6}" base))
      (let [value (cond-> {:base (mapv #(/ (Integer/parseInt (subs base % (+ % 2)) 16) 255.0) [1 3 5])
                           :metalness (Double/parseDouble metalness) :roughness (Double/parseDouble roughness)
                           :paint (or paint "")}
                    (contains? params "glow") (assoc :glow (Double/parseDouble glow)))]
        (when (scheme/material? value) value)))
    (catch Exception _ nil)))

(defn parse-detail
  "Detail strokes require a complete material finish."
  [{:strs [color] :as params}]
  (some-> (parse-material (assoc params "base" color))
          (select-keys [:base :metalness :roughness :glow])))
