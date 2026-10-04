(ns shipyard.part-browser.views
  "Shared metadata fields for individual parts and table drawers."
  (:require [shipyard.vocabulary.views :as vocabulary]))

(defn metadata-fields [prefix part]
  (list
   [:label.part-metadata__field "Name" [:input {:name "name" :value (:part/name part) :required true}]]
   (vocabulary/field-picker (str prefix "-bundle") "bundle" "Bundle / faction" (:part/bundle part))
   (vocabulary/field-picker (str prefix "-class") "class" "Class" (:part/class part))
   (vocabulary/field-picker (str prefix "-role") "role" "Role" (name (or (:part/role-hint part) :unknown)))))
