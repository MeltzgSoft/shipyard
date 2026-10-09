(ns shipyard.importer.transforms
  "Archive naming hints and destination planning, independent of IO."
  (:require [clojure.string :as str]
            [shipyard.library.scan :as scan]
            [shipyard.vocabulary.transforms :as vocabulary]))

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

(defn- match-text [s]
  (-> s (str/lower-case) (str/replace #"[\s_-]+" " ") (str/trim)))

(defn inference-rules
  "Compile one immutable shared vocabulary snapshot for an import review.
  Built-in roles retain the scanner's specialized precedence and exclusions."
  [values]
  (update-vals values
               (fn [labels]
                 (mapv (fn [label]
                         (let [text (match-text label)]
                           {:value label :specificity (count text)
                            :pattern (re-pattern (str "(?<![\\p{L}\\p{N}])"
                                                      (java.util.regex.Pattern/quote text)
                                                      "(?![\\p{L}\\p{N}])"))}))
                       labels))))

(defn- closest-hint [chain rules fallback]
  (some (fn [segment]
          (let [text (match-text (stem segment))
                candidates (filter #(re-find (:pattern %) text) rules)
                builtin (fallback segment)
                candidates (cond-> candidates builtin (conj builtin))
                best (when (seq candidates) (apply max (map :specificity candidates)))
                values (set (map :value (filter #(= best (:specificity %)) candidates)))]
            ;; A tied closest marker is unresolved, rather than depending on set order.
            (when (seq values) {:value (when (= 1 (count values)) (first values))})))
        (reverse (mapcat components chain))))

(defn class-hint
  ([chain] (class-hint chain {}))
  ([chain rules]
   (:value (closest-hint chain (:class rules)
                         (fn [s] (some (fn [[pattern label]]
                                         (when (re-find pattern s)
                                           {:value label :specificity (count (match-text label))})) class-rules))))))

(defn part-name [chain]
  (let [leaf (stem (last (components (last chain))))
        leaf (if (re-matches #"(?i)(unsupported(?:-pitted)?|supported)" leaf)
               (or (last (butlast (components (last chain)))) leaf) leaf)]
    (-> leaf
        (str/replace #"(?i)unsupported[ _-]*pitted|un[ _-]*supported|pre[ _-]*supported|supported" "")
        (str/replace #"(?i)\(repaired\)" "")
        (str/replace #"^[ _-]+|[ _-]+$" "")
        (clean-label))))

(defn infer
  ([id chain] (infer id chain {}))
  ([id chain rules]
   (let [name (part-name chain)
         class (class-hint chain rules)
         custom-roles (remove #(contains? (:role vocabulary/builtins) (:value %)) (:role rules))
         ;; Print-variant suffixes describe source files, never custom part roles.
         role-chain (->> (mapcat components chain)
                         (remove #(re-matches #"(?i)(original|supported|unsupported|pre[ _-]*supported)[ _-]+files" (stem %)))
                         (map #(part-name [%]))
                         (remove str/blank?))
         role-hint (when (closest-hint role-chain custom-roles (constantly nil))
                     (closest-hint role-chain custom-roles
                                   (fn [segment]
                                     (let [[role] (scan/role-hint {:name (stem segment) :class class})]
                                       (when-not (= :unknown role) {:value (clojure.core/name role) :specificity 0})))))
         [role source] (if role-hint
                         [(if-let [value (:value role-hint)] (keyword value) :unknown) :inferred]
                         (scan/role-hint {:name name :class class}))
         bundle-hint (closest-hint chain (:bundle rules) (constantly nil))
         variant (variant-hint chain)]
     {:part/id id :part/name name :part/bundle (or (:value bundle-hint) (clean-label (stem (first chain))))
      :part/class class :part/role-hint role :part/role-source source
      :part/variants #{variant} :part/source (when (= variant :unsupported) :unsupported)
      :part/renderable (= variant :unsupported)})))

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
  (let [parts (into {} (map (juxt :part/id identity)) parts)
        files (mapv (fn [{:keys [group variant] :as entry}]
                      (let [part (get parts group)]
                        (when-not part (throw (ex-info "An imported file has no review row. Restart the import." {})))
                        (merge entry (destination part variant) {:part part}))) (vals entries))
        groups (group-by #(str/lower-case (:path %)) files)]
    (doseq [[id files] (group-by #(str/lower-case (:id %)) files)]
      (when (> (count (set (map :group files))) 1)
        (throw (ex-info (str "Separate rows share a destination: " id ". Rename them or group them before importing.") {}))))
    (doseq [[path group] groups]
      (when (> (count (set (map :sha group))) 1)
        (throw (ex-info (str "Different files share a variant destination: " path
                             ". Assign a different supported/unsupported variant or split the group before importing.") {}))))
    (->> groups (vals) (map first) (sort-by :path) (vec))))

(def variants #{:supported :unsupported :unsupported-pitted})

(defn file-preview-id [file-key] (str "file-" file-key))

(defn members [entries group-id]
  (->> (vals entries) (filter #(= group-id (:group %))) (sort-by :key) (vec)))

(defn preview-entry [files]
  (let [unsupported (filter #(= :unsupported (:variant %)) files)]
    (when (= 1 (count (set (map :sha unsupported)))) (first unsupported))))

(defn thumbnail-entry
  "Prefer the orientation source; supported-only review rows may show scaffolding."
  [files]
  (if (some #(= :unsupported (:variant %)) files)
    (preview-entry files)
    (let [supported (filter #(= :supported (:variant %)) files)]
      (when (= 1 (count (set (map :sha supported)))) (first supported)))))

(defn matches-variant? [variant part]
  (or (str/blank? variant) (contains? (set (:part/variants part)) (keyword variant))))

(defn inferred-entries
  "Pair matching inferred labels only when each variant has unambiguous content.
  Identical repeated downloads can share a row; ambiguous candidates stay separate."
  ([entries] (inferred-entries entries {}))
  ([entries rules]
   (->> (vals entries)
        (group-by #(-> (infer (:key %) (:chain %) rules)
                       (select-keys [:part/name :part/bundle :part/class :part/role-hint])
                       (update-vals (fn [v] (if (string? v) (str/lower-case v) v)))))
        (vals)
        (mapcat (fn [files]
                  (let [unambiguous? (every? #(= 1 (count (set (map :sha %))))
                                             (vals (group-by :variant files)))
                        group-id (:key (or (preview-entry (sort-by :key files)) (first (sort-by :key files))))]
                    (map #(assoc % :group (if unambiguous? group-id (:key %))) files))))
        (map (juxt :key identity))
        (into {}))))

(defn review-parts
  "Project file membership into catalog parts, retaining labels and only a pose
  that still belongs to the same unsupported source. Labels may seed new groups."
  ([entries previous-entries labels] (review-parts entries previous-entries labels {}))
  ([entries previous-entries labels rules]
   (mapv (fn [[id files]]
           (let [source (preview-entry files)
                 seed (or (get labels id)
                          (infer id (:chain (or source (first files))) rules))
                 old-source (preview-entry (members previous-entries (:part/id seed)))]
             (cond-> (assoc seed :part/id id
                            :part/variants (set (map :variant files))
                            :part/renderable (boolean source)
                            :part/source (when source :unsupported))
               (not (and source old-source (= (:key source) (:key old-source))))
               (dissoc :part/orientation))))
         (sort-by key (group-by :group (sort-by :key (vals entries)))))))

(defn group-selection
  "Group the chosen rows and finish that selection, including rows hidden by filters."
  [entries parts ids group-name]
  (let [ids (set ids)
        selected (filter #(ids (:group %)) (vals entries))
        seed-id (:group (or (preview-entry (sort-by :key selected)) (first (sort-by :key selected))))
        seed (get parts seed-id)]
    (when (or (< (count ids) 2) (some #(not (contains? parts %)) ids)
              (not= ids (set (map :group selected))))
      (throw (ex-info "Select at least two available rows to group." {})))
    (when (and (not (str/blank? group-name)) (not (safe-segment? group-name)))
      (throw (ex-info "The group name must be a valid folder name." {})))
    {:entries (update-vals entries #(cond-> % (ids (:group %)) (assoc :group seed-id)))
     :labels (cond-> parts (not (str/blank? group-name)) (assoc seed-id (assoc seed :part/name group-name)))
     :selected []}))

(defn split-group [entries parts id]
  (let [files (members entries id)
        part (get parts id)]
    (when (or (nil? part) (< (count files) 2))
      (throw (ex-info "Choose a grouped row to split." {})))
    {:entries (reduce #(assoc-in %1 [(:key %2) :group] (:key %2)) entries files)
     :labels (reduce (fn [labels [i file]]
                       (assoc labels (:key file)
                              (assoc part :part/name (str (:part/name part) " (" (name (:variant file)) " " (inc i) ")"))))
                     parts (map-indexed vector files))
     :selected (mapv :key files)}))

(defn assign-variant
  "Changing one side of an unambiguous pair swaps the occupied variant, so the
  user can reverse a pair in one action. Ambiguous groups remain editable."
  [entries file-id variant]
  (when-not (and (contains? entries file-id) (variants variant))
    (throw (ex-info "Choose an available file and a valid supported/unsupported variant." {})))
  (let [{:keys [group] old :variant} (get entries file-id)
        files (members entries group)
        occupied (filter #(and (not= file-id (:key %)) (= variant (:variant %))) files)
        unique? (= (count files) (count (set (map :variant files))))]
    (cond-> (assoc-in entries [file-id :variant] variant)
      (and unique? (= 1 (count occupied))) (assoc-in [(:key (first occupied)) :variant] old))))
