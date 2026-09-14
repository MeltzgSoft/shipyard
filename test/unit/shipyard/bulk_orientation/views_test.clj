(ns shipyard.bulk-orientation.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.bulk-orientation.views :as views]
            [shipyard.http.htmx :as htmx]))

(def ^:private hull
  #:part{:id "Fleet/Cruiser/Hull" :bundle "Fleet" :class "Cruiser" :name "Hull"
         :variants [:unsupported] :renderable true :role-hint :hull :role-source :inferred})

(def ^:private supported-only
  #:part{:id "Fleet/Cruiser/Prow" :bundle "Fleet" :class "Cruiser" :name "Prow"
         :variants [:supported] :renderable false :role-hint :prow :role-source :inferred})

(defn- render [view]
  (htmx/html view))

(deftest panel-and-grid-test
  (let [picker (render (views/panel {:bundles ["Fleet"] :classes ["Cruiser"] :roles [:hull]}))
        results (render (views/results [hull supported-only]))
        grid (render (views/grid
                      [(merge hull {:state :ready :mesh-key "abc" :mesh-url "/mesh/abc.0.symesh"})]))]
    (testing "the picker reuses catalog facets and excludes unpreviewable rows from selection"
      (is (str/includes? picker "Orientation unset"))
      (is (str/includes? picker "data-bulk-render"))
      (is (str/includes? results "Prow"))
      (is (re-find #"data-bulk-select=\"true\"[^>]*disabled" results)))
    (testing "the viewport grid carries mesh identity and shared controls"
      (is (str/includes? grid "data-bulk-part"))
      (is (str/includes? grid "/mesh/abc.0.symesh"))
      (is (str/includes? grid "Back to table"))
      (is (str/includes? grid "data-bulk-step=\"90\""))
      (is (str/includes? grid "Copy first"))
      (is (str/includes? grid "Save orientations")))))
