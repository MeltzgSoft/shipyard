(ns shipyard.regions.colors-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [shipyard.regions.colors :as colors]
            [shipyard.regions.registry :as registry]
            [shipyard.regions.model :as model]))

(deftest separated-and-stable-colors
  (let [names ["Trim" "Running lights" "Armor" "Engines" "Torpedo tube"]
        legacy (assoc registry/empty-registry :layers
                      (into {} (map-indexed (fn [i name] [(str "layer:00000000-0000-4000-8000-00000000000" i)
                                                          {:name name :preview-name name}]) names)))
        assigned (registry/assign-colors legacy)
        palette (mapv :preview-color (vals (:layers assigned)))
        all (into colors/builtins palette)]
    (is (registry/valid? assigned))
    (is (= assigned (registry/assign-colors assigned)))
    (is (= assigned (registry/assign-colors (update legacy :layers #(into (sorted-map) %)))))
    (doseq [a all b all :when (not= a b)]
      (is (> (colors/distance a b) 0.12) "Five custom types and both builtins remain visually separated"))
    (doseq [[id entry] (:layers assigned)]
      (is (= (:preview-color entry)
             (get-in (model/preview-materials {:layers [id] :layer-definitions {id entry}}) [id :base])))
      (let [renamed (:registry (registry/change assigned 0 "rename" id "Renamed" nil))]
        (is (= palette (mapv :preview-color (vals (:layers renamed)))))))
    (let [id "layer:00000000-0000-4000-8000-000000000005"
          added (:registry (registry/change assigned 0 "add" nil "Another" id))
          deleted (:registry (registry/change added 1 "delete" id nil nil))]
      (is (= (:layers assigned) (dissoc (:layers added) id)))
      (is (= (:layers assigned) (:layers deleted))))))

(deftest deterministic-allocation
  (is (= (colors/choose []) (colors/choose [])))
  (is (= (colors/choose colors/builtins) (colors/choose (reverse colors/builtins)))))

(deftest valid-preview-color
  (is (colors/valid? [0 0.5 1]))
  (doseq [rgb [nil [0 1] [0 0 2] [0 "red" 1]]]
    (is (not (colors/valid? rgb)))))
