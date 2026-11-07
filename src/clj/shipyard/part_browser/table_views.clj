(ns shipyard.part-browser.table-views
  "Read-only browser columns and filters shared by the library and inventory picker."
  (:require [shipyard.http.urls :as urls]
            [shipyard.part-browser.transforms :as parts]
            [shipyard.thumbnail.views :as thumbnails]
            [shipyard.workspace.transforms :as workspace]))

(defn filters [facets values]
  (workspace/selected-filters
   [:div.part-browser-filters
    [:label.filters__field "Name" [:input {:type "search" :name "q" :placeholder "Search names"}]]
    (for [[field label key empty-label] [["bundle" "Bundle / faction" :bundles "All bundles"]
                                         ["class" "Class" :classes "All classes"]
                                         ["role" "Role" :roles "All roles"]]]
      [:label.filters__field label
       [:select {:name field}
        [:option {:value ""} empty-label]
        (for [value (get facets key)] [:option {:value (name value)} (name value)])]])] values))

(defn headings []
  (list [:span "Preview"] [:span "Part"] [:span "Bundle / faction"] [:span "Role"] [:span "Class"]))

(defn cells
  ([part root] (cells part root nil))
  ([part root reason]
   (let [thumbnail? (parts/thumbnail? part (:import/source part))]
     (list [:span.part-thumbnail
            (if thumbnail? (thumbnails/lazy-attrs (str "/thumbnails/" (urls/encode-id (:part/id part))) root true) {:title reason})
            (if thumbnail? "…" "No preview")]
           [:span.bulk-orient__part (:part/name part)]
           [:span.bulk-orient__bundle (:part/bundle part)]
           [:span.bulk-orient__role (name (or (:part/role-hint part) :unknown))]
           [:span.bulk-orient__class (or (:part/class part) "—")]))))

(defn variant-headings []
  (for [[_ variant label] parts/variant-filters]
    [:span {:title (when (= variant :unsupported-pitted) "Unsupported (pitted / recessed)")} label]))

(defn variant-cells [part]
  (for [[_ variant label] parts/variant-filters
        :let [available? (contains? (set (:part/variants part)) variant)]]
    [:span.bulk-orient__variant {:data-variant (name variant) :data-available (str available?)
                                 :title (str (if (= variant :unsupported-pitted) "Unsupported (pitted / recessed)" label) " file")}
     (if available? "Yes" "No")]))
