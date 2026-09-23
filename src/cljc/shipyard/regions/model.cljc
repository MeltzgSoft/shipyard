(ns shipyard.regions.model
  "Reusable, source-bound part regions. Faces belong to one shared layer name."
  (:require [clojure.string :as str]
            [shipyard.paint.faces :as faces]))

(def builtins ["Primary" "Secondary"])
(defn name? [value]
  (and (string? value) (<= 1 (count value) 200) (= value (str/trim value))))
(defn empty-regions [mesh-key] {:mesh-key mesh-key :revision 0 :layers builtins :faces {}})
(defn valid? [value]
  (and (map? value) (= #{:mesh-key :revision :layers :faces} (set (keys value)))
       (string? (:mesh-key value)) (boolean (re-matches #"[0-9a-f]{64}" (:mesh-key value)))
       (nat-int? (:revision value)) (vector? (:layers value))
       (= builtins (vec (take 2 (:layers value))))
       (every? name? (:layers value)) (= (count (:layers value)) (count (set (:layers value))))
       (map? (:faces value))
       (every? (fn [[key layer]] (and (faces/key? key) (contains? (set (:layers value)) layer))) (:faces value))))

(defn without-layer [regions layer]
  (if (and (not (some #{layer} builtins)) (some #{layer} (:layers regions)))
    (-> regions
        (update :layers #(filterv (complement #{layer}) %))
        (update :faces #(into {} (remove (fn [[_ name]] (= name layer))) %))
        (update :revision inc))
    regions))

(defn change
  ([regions mesh-key revision action layer new-name keys]
   (change regions mesh-key revision action layer new-name keys []))
  ([regions mesh-key revision action layer new-name keys available-layers]
   (let [current (or regions (empty-regions mesh-key)) layers (:layers current)]
     (cond
       (not= revision (:revision current)) {:error "Regions changed. Reopen this part before retrying."}
       (and (not= action "reset") (not= mesh-key (:mesh-key current)))
       {:error "Source mesh changed. Reset regions before assigning faces to this source."}
       (= action "reset") {:regions (assoc (empty-regions mesh-key) :revision (inc revision))}
       (= action "add")
       (if (and (name? new-name) (not (some #{new-name} layers)))
         {:regions (-> current (update :layers conj new-name) (update :revision inc))}
         {:error "Enter a unique layer name between 1 and 200 characters."})
       (not (or (some #{layer} layers)
                (and (= action "assign") (some #{layer} available-layers))))
       {:error "Choose an existing layer."}
       (= action "assign")
       (if (and (vector? keys) (seq keys) (every? faces/key? keys))
         {:regions (-> current
                       (update :layers #(if (some #{layer} %) % (conj % layer)))
                       (update :faces #(if (= layer "Primary") (apply dissoc % keys)
                                           (reduce (fn [m key] (assoc m key layer)) % keys)))
                       (update :revision inc))}
         {:error "Choose visible faces to assign."})
       (and (= action "rename") (not (some #{layer} builtins)))
       (if (and (name? new-name) (not (some #{new-name} layers)))
         {:regions (-> current
                       (update :layers #(mapv (fn [name] (if (= name layer) new-name name)) %))
                       (update :faces #(into {} (map (fn [[key name]] [key (if (= name layer) new-name name)])) %))
                       (update :revision inc))}
         {:error "Enter a unique layer name between 1 and 200 characters."})
       (and (= action "delete") (not (some #{layer} builtins)))
       {:regions (without-layer current layer)}
       :else {:error "Primary and Secondary cannot be renamed or deleted."}))))

(defn- preview-color [name]
  (case name
    "Primary" [0.6 0.65 0.7]
    "Secondary" [0.15 0.6 0.95]
    ;; Hash UTF-16 code units identically on the JVM and in the browser.
    ;; A layer's color must not depend on other names or their ordering.
    (let [hue (/ (reduce (fn [value ch]
                           (mod (+ (* 31 value) #?(:clj (int ch) :cljs (.charCodeAt ch 0))) 360))
                         0 name) 60.0)
          c 0.72 x (* c (- 1 (abs (- (mod hue 2) 1))))
          rgb (case (int hue)
                0 [c x 0] 1 [x c 0] 2 [0 c x]
                3 [0 x c] 4 [x 0 c] 5 [c 0 x])]
      (mapv #(+ 0.19 %) rgb))))

(defn preview-materials [regions]
  (into {} (map (fn [name] [name {:base (preview-color name)
                                  :metalness 0.05 :roughness 0.65}]) (:layers regions))))
