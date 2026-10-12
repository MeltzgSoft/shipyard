(ns shipyard.fleet.editor-transforms
  "Pure editor form decisions; durable writes and preview effects live at boundaries."
  (:require [clojure.string :as str]))

(defn quantity [value]
  (when (and (string? value) (re-matches #"[0-9]+" value))
    (let [n (parse-long value)] (when (and n (pos? n)) n))))

(defn move-variant [order variant direction]
  (let [i (.indexOf ^java.util.List order variant)
        j (+ i (case direction :up -1 :down 1 0))]
    (if (and (<= 0 i) (< -1 j (count order)))
      (assoc order i (nth order j) j variant)
      order)))

(defn change [record action params]
  (let [q (quantity (get params "quantity"))
        class (some-> (get params "class-id") parse-uuid)
        part (not-empty (get params "part-id"))
        name (some-> (get params "name") (str/trim))]
    (case action
      :rename (if (and name (< 0 (count name) 121)) {:fleet (assoc record :fleet/name name)}
                  {:error "Enter a fleet name of 1–120 characters."})
      :class-set (if (and q class) {:fleet (assoc-in record [:fleet/entries class] q)}
                     {:error "Choose a saved class and enter a positive integer quantity."})
      :class-remove (if class {:fleet (update record :fleet/entries dissoc class)} {:error "Choose a saved class."})
      :extra-set (if (and q part) {:fleet (assoc-in record [:fleet/extras part] q)}
                     {:error "Choose a library part and enter a positive integer quantity."})
      :extra-remove (if part {:fleet (update record :fleet/extras dissoc part)} {:error "Choose a library part."})
      :variant {:fleet (update record :fleet/variant-preference move-variant
                               (keyword (get params "variant")) (keyword (get params "direction")))}
      {:error "This fleet action is unavailable."})))

(defn selected? [selection params]
  (and (uuid? selection) (= selection (some-> (get params "id") parse-uuid))))
