(ns shipyard.export.transforms
  "Pure print inventory and per-part source selection."
  (:require [clojure.string :as str]))

(def default-priority [:unsupported-pitted :unsupported :supported])

(defn priority? [order]
  (and (vector? order) (= (count order) (count default-priority))
       (= (set order) (set default-priority))))

(defn move [order variant direction]
  (let [i (.indexOf ^java.util.List order variant) j (+ i direction)]
    (if (and (not (neg? i)) (<= 0 j) (< j (count order)))
      (assoc order i (nth order j) j variant) order)))

(defn inventory [classes]
  (reduce (fn [counts record]
            (merge-with +' counts
                        (frequencies (cons (:loadout/hull record) (vals (:loadout/slots record))))
                        (:loadout/extras record))) {} classes))

(defn source-path [path]
  (let [segments (str/split path #"/" -1)]
    (when (or (str/blank? path) (str/includes? path "\\")
              (str/includes? path ":") (some #{"" "." ".."} segments)
              (some #(Character/isISOControl ^char %) path))
      (throw (ex-info "Unsafe library-relative source path." {:path path})))
    path))

(defn- safe-name [value]
  (let [label (-> (str value) (str/replace #"[\\/\p{Cntrl}:*?\"<>|]" "-")
                  (str/replace #"^[. ]+|[. ]+$" ""))]
    (cond
      (str/blank? label) "Unnamed"
      (re-matches #"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?" label) (str "_" label)
      :else label)))

(defn- unique-labels [entries]
  (:labels
   (reduce (fn [{:keys [used] :as result} [id label]]
             (let [base (safe-name label)
                   candidate (first (remove #(contains? used (str/lower-case %))
                                            (cons base (map #(str base " (" % ")") (iterate inc 2)))))]
               (-> result (update :used conj (str/lower-case candidate)) (assoc-in [:labels id] candidate))))
           {:used #{} :labels {}} entries)))

(defn- ship-plan [record directory parts sources priority]
  (let [counts (inventory [record])
        labels (unique-labels (map (fn [id] [id (or (:part/name (get parts id)) (some-> id (str/split #"/") (last)) "Unavailable")])
                                   (sort (keys counts))))]
    (mapv (fn [[id quantity]]
            (when-not (and id (get parts id) (not (false? (:part/present? (get parts id)))))
              (throw (ex-info (str "Required part is unavailable: " id ". Restore it before exporting.") {:part-id id})))
            (let [by-variant (group-by :variant (get sources id))
                  source (some #(first (get by-variant %)) priority)]
              (when-not source
                (throw (ex-info (str "No available source variant for " id ". Rescan the library.") {:part-id id})))
              (source-path (:path source))
              (assoc source :part-id id :quantity quantity
                     :ship-id (:loadout/id record) :ship-name (:loadout/name record) :ship-directory directory
                     :archive-path (str directory "/" quantity "x-" (get labels id) "-" (name (:variant source)) ".stl"))))
          (sort-by key counts))))

(defn plan [classes parts sources priority]
  (when-not (priority? priority) (throw (ex-info "Choose each source variant exactly once." {})))
  (let [classes (sort-by (comp str :loadout/id) classes)
        directories (unique-labels (map (juxt :loadout/id :loadout/name) classes))
        sources (group-by :owner sources)]
    (vec (mapcat #(ship-plan % (get directories (:loadout/id %)) parts sources priority) classes))))
