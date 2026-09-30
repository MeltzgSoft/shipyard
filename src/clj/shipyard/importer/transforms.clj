(ns shipyard.importer.transforms
  "Archive naming hints and destination planning, independent of IO."
  (:require [clojure.string :as str]
            [shipyard.library.scan :as scan]))

(defn components [path] (str/split path #"[/\\]+"))
(defn stem [s] (str/replace s #"(?i)\.(zip|stl)$" ""))

(defn safe-segment? [s]
  (and (string? s) (not (str/blank? s))
       (not (#{"." ".." "other"} (str/lower-case s)))
       (not (re-find #"[<>:\"/\\|?*\p{Cntrl}]|[. ]$" s))
       (not (re-find #"(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)" s))))

(defn clean-label [s]
  (-> s (str/replace #"[<>:\"/\\|?*\p{Cntrl}]" " ")
      (str/replace #"[_\s]+" " ") (str/trim) (str/replace #"[. ]+$" "")))

(defn variant-hint [chain]
  (or (some (fn [s]
              (cond
                (re-find #"(?i)unsupported[ _-]*pitted" s) :unsupported-pitted
                (re-find #"(?i)unsupported|original[ _-]*files|un[ _-]*supported" s) :unsupported
                (re-find #"(?i)supported|pre[ _-]*supported" s) :supported))
            (reverse (mapcat components chain)))
      :unsupported))

(def class-rules
  [[#"(?i)grand[ _-]*cruiser|\bGC\b" "Grand Cruiser"]
   [#"(?i)light[ _-]*cruiser|\bLC\b" "Light Cruiser"]
   [#"(?i)battle[ _-]*cruiser" "Battlecruiser"]
   [#"(?i)battle?s?hip|batleship|\bBB\b" "Battleship"]
   [#"(?i)cruiser|krooza" "Cruiser"]
   [#"(?i)escort|frigate|destroyer" "Escort"]
   [#"(?i)ordnance|ordinance|assault[ _-]*boats|fighters|bombers" "Ordnance"]])

(defn class-hint [chain]
  (some (fn [s] (some (fn [[pattern label]] (when (re-find pattern s) label)) class-rules))
        (reverse (mapcat components chain))))

(defn part-name [chain]
  (let [leaf (stem (last (components (last chain))))
        leaf (if (re-matches #"(?i)(unsupported(?:-pitted)?|supported)" leaf)
               (or (last (butlast (components (last chain)))) leaf) leaf)]
    (-> leaf
        (str/replace #"(?i)unsupported[ _-]*pitted|un[ _-]*supported|pre[ _-]*supported|supported" "")
        (str/replace #"(?i)\(repaired\)" "")
        (str/replace #"^[ _-]+|[ _-]+$" "")
        (clean-label))))

(defn infer [id chain]
  (let [name (part-name chain)
        class (class-hint chain)
        [role source] (scan/role-hint {:name name :class class})
        variant (variant-hint chain)]
    {:part/id id :part/name name :part/bundle (clean-label (stem (first chain)))
     :part/class class :part/role-hint role :part/role-source source
     :part/variants #{variant} :part/source (when (= variant :unsupported) :unsupported)
     :part/renderable (= variant :unsupported)}))

(defn destination [part variant]
  (let [segments (concat [(:part/bundle part)]
                         (when-not (str/blank? (:part/class part)) [(:part/class part)])
                         (when (#{:weapon :turret} (:part/role-hint part)) ["weapons"])
                         (when (= :turret (:part/role-hint part)) ["turrets"])
                         [(:part/name part)])]
    (when-not (every? safe-segment? segments)
      (throw (ex-info "Bundle, class and part names must be valid folder names." {:part (:part/id part)})))
    {:id (str/join "/" segments)
     :path (str (str/join "/" segments) "/" (name variant) ".stl")}))

(defn plan [parts entries]
  (let [files (mapv (fn [part]
                      (let [{:keys [variant] :as entry} (get entries (:part/id part))]
                        (merge entry (destination part variant) {:part part}))) parts)
        groups (group-by #(str/lower-case (:path %)) files)]
    (doseq [[path group] groups]
      (when (or (> (count (set (map :sha group))) 1)
                (> (count (set (keep #(get-in % [:part :part/orientation]) group))) 1)
                (> (count (set (map #(get-in % [:part :part/role-hint]) group))) 1))
        (throw (ex-info (str "Different files or reviewed values share a destination: " path ". Rename the conflicting parts before importing.") {}))))
    (->> groups (vals)
         (map #(or (some (fn [entry] (when (get-in entry [:part :part/orientation]) entry)) %) (first %)))
         (sort-by :path) (vec))))
