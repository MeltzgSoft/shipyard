(ns shipyard.part-browser.variant-plan
  "Pure decisions for canonical library variant file moves."
  (:require [clojure.string :as str]))

(def variants #{:supported :unsupported :unsupported-pitted})

(defn- ensure! [condition message]
  (when-not condition (throw (ex-info message {:code :invalid-variant-edit}))))

(defn- file-path [owner variant] (str owner "/" (name variant) ".stl"))

(defn plan [parts files action {:keys [ids group file variant] group-name :name}]
  (let [by-owner (group-by :owner files)
        selected (mapv parts (distinct ids))
        group-files (get by-owner group)
        chosen (first (filter #(= file (:key %)) files))
        updated
        (case action
          :group
          (do
            (ensure! (and (>= (count selected) 2) (every? #(and (:part/id %) (not (false? (:part/present? %))) (seq (get by-owner (:part/id %)))) selected)) "Select at least two available rows.")
            (let [members (mapcat #(get by-owner (:part/id %)) selected)
                  anchor (or (:owner (first (filter #(= :unsupported (:variant %)) members))) (:part/id (first selected)))
                  losing (remove #(= anchor (:part/id %)) selected)]
              (ensure! (not-any? :protected? losing) "A row being merged has authored mounts, paint, or saved-ship references. Keep that row separate.")
              (mapv #(if (contains? (set ids) (:owner %)) (assoc % :owner anchor) %) files)))
          :split
          (do
            (ensure! (> (count group-files) 1) "This row has only one file.")
            (let [keep-file (or (first (filter #(= :unsupported (:variant %)) group-files)) (first group-files))
                  departing (remove #(= (:key keep-file) (:key %)) group-files)
                  destinations
                  (reduce (fn [destinations entry]
                            (let [original (:origin entry)
                                  reserved (set (vals destinations))
                                  restored? (and original (not= group original) (empty? (get by-owner original)) (not (reserved original)))
                                  stem (str group " - " (name (:variant entry)))
                                  target (if restored? original
                                             (first (remove #(or (contains? parts %) (seq (get by-owner %)) (reserved %))
                                                            (cons stem (map #(str stem " " %) (range 2 10000))))))]
                              (ensure! target "No free folder is available for this split.")
                              (assoc destinations (:key entry) target))) {} departing)]
              (mapv #(if-let [target (destinations (:key %))] (assoc % :owner target) %) files)))
          :variant
          (do
            (ensure! (and chosen (variants variant)) "Choose an available file and a valid variant.")
            (let [occupied (first (filter #(and (= (:owner chosen) (:owner %)) (= variant (:variant %))) files))]
              (mapv #(cond (= file (:key %)) (assoc % :variant variant)
                           (and occupied (= (:key occupied) (:key %))) (assoc % :variant (:variant chosen))
                           :else %) files)))
          (throw (ex-info "Choose a grouping action." {})))
        grouped (group-by (juxt :owner :variant) updated)
        old-unsupported (into {} (map (juxt :owner :key)) (filter #(= :unsupported (:variant %)) files))
        new-unsupported (into {} (map (juxt :owner :key)) (filter #(= :unsupported (:variant %)) updated))
        changed (set (for [id (into (set (keys old-unsupported)) (keys new-unsupported))
                           :when (not= (old-unsupported id) (new-unsupported id))] id))
        moved (for [[before after] (map vector files updated)
                    :when (not= (select-keys before [:owner :variant]) (select-keys after [:owner :variant]))]
                (assoc after :before before :path (file-path (:owner after) (:variant after))))]
    (ensure! (every? #(= 1 (count %)) (vals grouped)) "Two files have the same variant. Assign distinct variants before grouping.")
    (ensure! (not-any? #(and (:protected? (parts %)) (changed %)) (keys parts))
             "Changing the unsupported source would invalidate authored mounts, paint, or saved-ship references.")
    {:moves (vec moved) :changed-source changed
     :checked-files (case action :group (vec (mapcat #(get by-owner %) ids)) :split group-files :variant (get by-owner (:owner chosen)))
     :label (when (and (= action :group) (not (str/blank? group-name)))
              {:id (:owner (first moved)) :name (str/trim group-name)})
     :selected (case action
                 :group []
                 :variant [(:owner chosen)]
                 :split (vec (sort (distinct (map :owner (filter #(contains? (set (map :key group-files)) (:key %)) updated))))))}))
