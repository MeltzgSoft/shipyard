(ns shipyard.e2e.import-group-selection-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.fixtures :as meshes]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer])
  (:import [com.microsoft.playwright Page]))

(deftest successive-groups-do-not-absorb-a-completed-group-hidden-by-a-filter
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver) ^Page page (:page driver)
        ws (:shipyard.workspace/db sys) session! #(importer/session! {:workspace ws})
        zip (fs/file (:temp started) "Tiamat Fleet.zip")]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes
                            (map-indexed (fn [n filename]
                                           [(str "Light Cruiser/" filename) (meshes/->binary-stl (meshes/cube (+ 1.0 n)))])
                                         ["Grand Hull v30.stl" "Grand hull supported.stl" "Metis Hull v6.stl" "Metis Hull supported.stl"]))))
      (s/go! driver (s/base-url sys))
      (s/choose-path! driver ".import-start" zip)
      (when (pos? (s/count-els driver ".import-start button:text-is('Review archive')"))
        (s/click! driver ".import-start button:text-is('Review archive')"))
      (s/wait-visible! driver ".import-review")
      (is (= 4 (s/count-els driver "[data-bulk-select]")))
      (let [entries @(:entries (session!))
            grand (sort (for [[id entry] entries :when (str/includes? (str (:chain entry)) "Grand")] id))
            light (sort (for [[id entry] entries :when (str/includes? (str (:chain entry)) "Metis")] id))]
        (is (= 2 (count grand)))
        (is (= 2 (count light)))
        (doseq [id grand]
          (s/check! driver (str "[data-bulk-select][value='" id "']")))
        (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
        (s/select-option! driver ".part-bulk-edit select[name=field]" "Class")
        (.fill page ".part-bulk-edit input[name=value]" "Grand Cruiser")
        (s/click! driver "#part-bulk-apply")
        (is (s/wait-until #(every? (fn [id] (= "Grand Cruiser" (:part/class (catalog/summary! (:catalog (session!)) id)))) grand)))
        (s/click! driver "[data-import-group]")
        (is (s/wait-until #(= 3 (s/count-els driver "[data-bulk-select]"))))
        (s/wait-visible! driver "[data-bulk-count]:text-is('0 selected')")
        (let [grand-group (:group (get @(:entries (session!)) (first grand)))]
          (s/select-option! driver "#bulk-orient-filters select[name=class]" "Light Cruiser")
          (is (s/wait-until #(= 2 (s/count-els driver "[data-bulk-select]"))))
          (doseq [id light]
            (s/check! driver (str "[data-bulk-select][value='" id "']")))
          (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
          ;; The response waits for both the mutation and its replacement table.
          (.waitForResponse page "**/imports/group" ^Runnable #(s/click! driver "[data-import-group]"))
          (is (s/wait-until #(not (s/js driver "() => document.querySelector('[data-import-group]').disabled"))))
          (is (= 2 (count (set (map :group (vals @(:entries (session!))))))) "Two independent groups remain")
          (is (= (set grand) (set (for [[id entry] @(:entries (session!)) :when (= grand-group (:group entry))] id)))
              "Grand Cruiser retains exactly its own supported and unsupported files")
          (is (= "Grand Cruiser" (:part/class (catalog/summary! (:catalog (session!)) grand-group))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
