(ns shipyard.part-browser.transforms
  "Pure edits to authored catalog labels; source identity never changes."
  (:require [clojure.string :as str]
            [shipyard.vocabulary.transforms :as vocabulary]))

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
      (some #(and (string? (:value %)) (str/blank? (:value %))) changes)
      {:error "Names, bundles and classes cannot be blank."}
      :else {:changes changes})))
