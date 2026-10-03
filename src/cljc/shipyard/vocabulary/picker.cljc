(ns shipyard.vocabulary.picker
  "Suggestions for an editable classification selector; saving stays on the server."
  (:require [clojure.string :as str]))

(defn options [values query]
  (let [query (str/trim (or query ""))
        lower (str/lower-case query)
        values (sort (distinct values))
        matches (filter #(str/includes? (str/lower-case %) lower) values)]
    (cond-> (mapv #(hash-map :value % :new? false) matches)
      (and (seq query) (not-any? #(= lower (str/lower-case %)) values))
      (conj {:value query :new? true}))))
