(ns shipyard.fleet.plan
  "Pure projection shared by fleet readiness, preview and export."
  (:require [clojure.string :as str]))

(defn archive-path?
  "Only canonical library-relative STL paths may enter an archive."
  [path]
  (and (string? path) (not (str/blank? path))
       (not (re-find #"[\\:\x00]" path))
       (not (str/starts-with? path "/"))
       (every? #(not (contains? #{"" "." ".."} %)) (str/split path #"/" -1))
       (str/ends-with? (str/lower-case path) ".stl")))

(defn- problem [code message data]
  (merge {:code code :message message} data))

(defn requirements
  "Count saved hulls and every assigned full instance path, including repeats."
  [{:keys [fleet classes]}]
  (reduce-kv
   (fn [result id quantity]
     (let [class (get classes id)]
       (if (and class (= (:fleet/library fleet) (:loadout/library class)))
         (reduce #(update-in %1 [:quantities %2] (fnil + 0) quantity)
                 result (remove nil? (cons (:loadout/hull class) (vals (:loadout/slots class)))))
         (update result :errors conj
                 (problem :missing-class "A saved class is missing from this library." {:class-id id})))))
   {:quantities (:fleet/extras fleet) :errors []} (:fleet/entries fleet)))

(defn print-plan
  "Resolve each durable part to the first present, explicitly permitted source."
  [{:keys [fleet parts] :as snapshot}]
  (let [{:keys [quantities errors]} (requirements snapshot)
        result
        (reduce
         (fn [result [id quantity]]
           (let [part (get parts id)
                 source (some (fn [variant]
                                (let [source (get (:part/sources part) variant)]
                                  (when (:source/present? source) source)))
                              (:fleet/variant-preference fleet))
                 error (cond
                         (not (and part (:part/present? part)
                                   (= (:fleet/library fleet) (:part/library part))))
                         (problem :missing-part "A required part is missing from this library." {:part-id id})
                         (not (pos-int? quantity))
                         (problem :invalid-quantity "Part quantities must be positive integers." {:part-id id})
                         (nil? source)
                         (problem :unavailable-variant "No permitted source variant is available; adjust the fleet preference or restore the source." {:part-id id})
                         (not (archive-path? (:source/path source)))
                         (problem :invalid-source-path "A selected source has an invalid library-relative path." {:part-id id}))]
             (if error (update result :errors conj error)
                 (update result :files conj
                         {:part-id id :source-id (:source/key source) :path (:source/path source)
                          :variant (:source/variant source) :quantity quantity
                          :stamp {:size (:source/size source) :mtime (:source/mtime source)}
                          :content-hash (:source/content source)}))))
         {:files [] :errors errors} (sort-by key quantities))
        collisions (->> (:files result) (group-by :path) (filter #(> (count (val %)) 1)) (seq))]
    (cond-> (assoc result :ready? (and (empty? (:errors result)) (not collisions)))
      collisions (update :errors conj (problem :conflicting-source-path "Different parts resolve to the same archive path." {})))))
