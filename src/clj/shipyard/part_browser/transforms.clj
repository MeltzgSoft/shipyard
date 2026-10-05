(ns shipyard.part-browser.transforms
  "Pure edits to authored catalog labels and poses; source identity never changes."
  (:require [clojure.string :as str]
            [shipyard.vocabulary.transforms :as vocabulary]
            [shipyard.part.orientation :as orientation]))

(def variant-filters
  [["has-unsupported" :unsupported "Unsupported"]
   ["has-supported" :supported "Supported"]
   ["has-pitted" :unsupported-pitted "Pitted / recessed"]])

(defn matches-availability?
  "Combine available/missing variant requirements against scanned file types."
  [params part]
  (let [variants (set (:part/variants part))]
    (every? (fn [[field variant]]
              (case (get params field)
                (nil "") true
                "available" (contains? variants variant)
                "missing" (not (contains? variants variant))
                false))
            variant-filters)))

(defn listed? [part importing?]
  (or importing?
      (contains? (set (:part/variants part)) :unsupported)
      (not (contains? (set (:part/variants part)) :supported))))

(defn thumbnail? [part importing?]
  (boolean (or (:part/renderable part)
               (and importing? (= :supported (:part/source part))))))

(def fields
  {"bundle" [:part/bundle :part/bundle-override]
   "class" [:part/class :part/class-override]
   "role" [:part/role-hint :part/role-override]
   "name" [:part/name :part/name-override]})

(defn edits [parts {:strs [field operation value find]}]
  (let [[source target] (fields field)
        operation (if (= field "name") operation "set")
        value (or value "")
        changes (when target
                  (mapv (fn [part]
                          (let [old (str (get part source))
                                value (str/trim (case operation
                                                  "prefix" (str value old)
                                                  "suffix" (str old value)
                                                  "replace" (str/replace old (or find "") value)
                                                  value))]
                            {:id (:part/id part) :attribute target
                             :value (if (= field "role") (vocabulary/role value) value)})) parts))]
    (cond
      (empty? parts) {:error "Select at least one part."}
      (nil? target) {:error "Choose a field to edit."}
      (and (= operation "replace") (str/blank? find)) {:error "Enter the text to find."}
      (and (= field "role") (nil? (vocabulary/role value)))
      {:error "Choose a valid role."}
      (and (#{"bundle" "class"} field) (:error (vocabulary/entry field value)))
      (select-keys (vocabulary/entry field value) [:error])
      (some #(and (string? (:value %)) (str/blank? (:value %))) changes)
      {:error "Names, bundles and classes cannot be blank."}
      :else {:changes changes})))

(def angle-fields ["part-yaw-deg" "part-pitch-deg" "part-roll-deg"])

(defn row-edits [part params]
  (if-not part
    {:error "This part is unavailable. Refresh the table and retry."}
    (let [results (mapv (fn [field] (edits [part] {"field" field "operation" "set" "value" (get params field)}))
                        ["name" "bundle" "class" "role"])
          save-pose? (= "save" (get params "orientation-action"))
          pose (when (or save-pose? (some #(contains? params %) angle-fields))
                 (orientation/save-request (assoc (select-keys params angle-fields) "action" "save")))]
      (cond
        (some :error results) (first (filter :error results))
        (:error pose) {:error (:error pose)}
        (and save-pose? (not (:part/renderable part)))
        {:error "Orientation editing needs an unambiguous unsupported source. Assign variants or restore the source first."}
        :else {:changes (cond-> (vec (mapcat :changes results))
                          save-pose? (conj {:id (:part/id part) :attribute :part/orientation :value (:orientation pose)}))}))))
