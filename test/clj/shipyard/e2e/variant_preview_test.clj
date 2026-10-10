(ns shipyard.e2e.variant-preview-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.fixtures :as meshes]
            [shipyard.part-browser.variants :as variants]
            [shipyard.part-variants-fixture :as source]
            [shipyard.workspace.db :as workspace])
  (:import [com.microsoft.playwright Page]))

(defn- build! [root]
  (source/build! root)
  (doseq [[variant size] [["supported" 2] ["unsupported-pitted" 3]]]
    (with-open [out (io/output-stream (fs/file root source/a (str variant ".stl")))]
      (.write out ^bytes (meshes/->binary-stl (meshes/cube size)))))
  root)

(deftest variant-rows-open-exact-read-only-models-and-retain-browser-state
  (let [started (fixture/start! true build! (fn [_])) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) ws (:shipyard.workspace/db sys)
        files (filter #(= source/a (:owner %)) (variants/files! cat))
        _ (catalog/save-part-orientation! cat source/a [0 1 0 0])
        _ (catalog/save-mounts! cat source/a [fixture/plug])
        before (catalog/listing! cat) keys (atom #{}) row (str "[data-part-row='" source/a "']")]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver row)
      (s/check! driver (str row " [data-bulk-select]"))
      (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Battery")
      (s/select-option! driver "#bulk-orient-filters select[name=class]" "Cruiser")
      (s/wait-visible! driver row)
      (doseq [{:keys [key path]} files]
        (s/click! driver (str row " > summary"))
        (s/wait-visible! driver (str row " .import-files"))
        (s/click! driver (str row " .import-files > summary"))
        (.dblclick ^Page (:page driver) (str "[data-variant-file='" key "'] > span[title]"))
        (s/wait-visible! driver (str "[data-variant-preview='" key "']"))
        (s/await-part driver (str "library-file-" key))
        (swap! keys conj (get-in (s/stats driver) [:interfaces :mesh-key]))
        (is (zero? (get-in (s/stats driver) [:interfaces :count])))
        (is (= [0 0 0 1] (:orientation (s/stats driver))))
        (is (= path (s/text driver ".detail__name")))
        (is (= 1 (s/count-els driver "#detail button")))
        (is (zero? (s/count-els driver "#detail input, #detail select, #detail [data-detail-tab], #mount-authoring, #part-regions")))
        (is (nil? (:authoring (s/stats driver))))
        (is (= :variant (:view (workspace/workspace! ws :browse))))
        (is (= key (:variant-selection (workspace/workspace! ws :browse))))
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#settings-workspace")
        (s/click! driver "[data-workspace-mode=browse]")
        (s/await-part driver (str "library-file-" key))
        (is (= 1 (s/count-els driver "#detail button")))
        (s/click! driver "[data-part-back]")
        (s/wait-visible! driver row)
        (is (= "Battery" (s/js driver "() => document.querySelector('#bulk-orient-filters input[name=q]').value")))
        (is (= "Cruiser" (s/js driver "() => document.querySelector('#bulk-orient-filters select[name=class]').value")))
        (is (= "1 selected" (s/text driver "[data-bulk-count]"))))
      (is (= 3 (count @keys)) "Each variant loads its own source geometry")
      (is (= before (catalog/listing! cat)) "Variant inspection does not change authored parts or eligibility")
      (s/open-part! driver "Battery")
      (s/await-part driver source/a)
      (s/wait-visible! driver ".part-metadata__form")
      (is (= 3 (s/count-els driver ".detail__navigation button")))
      (finally (s/quit! driver) (fixture/stop! started)))))
