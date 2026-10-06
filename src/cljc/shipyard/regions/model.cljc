(ns shipyard.regions.model
  "Reusable, source-bound part regions. Faces reference stable shared layers."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def builtins schemas/builtins)
(def detail-id? (m/validator schemas/detail-id))
(def name? (m/validator schemas/layer-name))
(defn empty-regions [mesh-key]
  {:version 2 :mesh-key mesh-key :revision 0 :layers builtins :layer-definitions {} :faces {}})
(def valid? (m/validator schemas/regions))

(defn change
  ([regions mesh-key revision action layer keys]
   (change regions mesh-key revision action layer keys []))
  ([regions mesh-key revision action layer keys available-layers]
   (let [current (or regions (empty-regions mesh-key)) layers (:layers current)]
     (cond
       (not= revision (:revision current)) {:error "Regions changed. Reopen this part before retrying."}
       (and (not= action "reset") (not= mesh-key (:mesh-key current)))
       {:error "Source mesh changed. Reset regions before assigning faces to this source."}
       (= action "reset") {:regions (assoc (empty-regions mesh-key) :revision (inc revision))}
       (not (or (some #{layer} layers)
                (and (= action "assign") (some #{layer} available-layers))))
       {:error "Choose an existing layer."}
       (= action "assign")
       (if (schemas/valid-face-keys? keys)
         {:regions (-> current
                       (update :layers #(if (some #{layer} %) % (conj % layer)))
                       (update :faces #(if (= layer "Primary") (apply dissoc % keys)
                                           (reduce (fn [m key] (assoc m key layer)) % keys)))
                       (update :revision inc))}
         {:error "Choose visible faces to assign."})
       :else {:error "Unknown region operation."}))))

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
  (into {} (map (fn [name] [name {:base (or (get-in regions [:layer-definitions name :preview-color])
                                            (preview-color (get-in regions [:layer-definitions name :preview-name] name)))
                                  :metalness 0.05 :roughness 0.65}]) (:layers regions))))
