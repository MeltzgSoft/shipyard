(ns shipyard.e2e.importer-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.file-picker.db :as picker]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.fixtures :as meshes]
            [shipyard.library.index :as index])
  (:import [com.microsoft.playwright Page Request]
           [java.util.function Consumer]))

(deftest import-classification-selectors-reuse-and-stage-new-values
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        workspace (:shipyard.workspace/db sys) cat (:shipyard.catalog/db sys)
        directory (fs/create-temp-dir) zip (archives/archive! directory)
        session! #(importer/session! {:workspace workspace})
        before (catalog/listing! cat)]
    (try
      (s/go! driver (s/base-url sys))
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (s/click! driver "[data-select-all=all]")
      (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
      ;; Review combines its own choices with the destination library's values.
      (s/click! driver "[data-classification-toggle]")
      (s/click! driver "#part-edit-options [role=option]:text-is('Synthetic Navy')")
      (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#part-edit-value').value")))
      (doseq [[label field value saved] [["Bundle / faction" :part/bundle "Staged Fleet" "Staged Fleet"]
                                         ["Class" :part/class "Staged Carrier" "Staged Carrier"]
                                         ["Role" :part/role-hint "Sensor Array" :sensor-array]]]
        (s/select-option! driver ".part-bulk-edit select[name=field]" label)
        (.fill ^Page (:page driver) "#part-edit-value" value)
        (s/click! driver (str "#part-edit-options [role=option]:text-is('Add “" value "”')"))
        (s/click! driver "#part-bulk-apply")
        (is (s/wait-until #(every? (fn [part] (= saved (get part field)))
                                   (vals (:parts (catalog/listing! (:catalog (session!))))))))
        (is (= before (catalog/listing! cat)))
        (s/click! driver "[data-classification-toggle]")
        (s/click! driver (str "#part-edit-options [role=option]:text-is('" (if (keyword? saved) (name saved) saved) "')")))
      (s/click! driver "[data-classification-toggle]")
      (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-import-classification-selector.png"))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (is (= before (catalog/listing! cat)))
      (is (zero? (s/count-els driver "#classification-values option[value='Staged Fleet'], #classification-values option[value='Staged Carrier'], #classification-values option[value='sensor-array']")))
      (finally (s/quit! driver) (fixture/stop! started) (fs/delete-tree directory)))))

(deftest expanded-files-have-independent-lazy-thumbnails
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        zip (fs/file (:temp started) "Variants.zip") requests (atom [])]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes
                (archives/zip-bytes
                 (map-indexed (fn [n folder]
                                [(str folder "/Hull.stl")
                                 (meshes/->binary-stl
                                  (mapv (fn [triangle]
                                          (mapv (fn [[x y z]] [(* (inc n) x) y z]) triangle)) (meshes/cube)))])
                              ["Original Files" "Supported Files" "Unsupported Pitted"]))))
      (.onRequest page (reify Consumer (accept [_ request] (swap! requests conj (.url ^Request request)))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".import-start")
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (s/wait-visible! driver ".bulk-orient__row > .part-thumbnail img")
      (is (not-any? #(.contains ^String % "/imports/thumbnails/") @requests)
          "Collapsed variant cells do not prepare file previews")
      (s/click! driver ".import-files summary")
      (is (s/wait-until #(= 3 (s/count-els driver ".import-file-thumbnail img"))))
      (is (s/js driver "() => [...document.querySelectorAll('.import-file-thumbnail img')].every(i=>i.naturalWidth===128)"))
      (let [before (s/js driver "() => [...document.querySelectorAll('.import-files__file')].map(el=>[el.querySelector('select').dataset.importFile,el.querySelector('img').getAttribute('src')])")]
        (is (= 3 (count (set (map second before)))) "Each source file gets its own content image")
        (s/screenshot-el! driver ".import-files" (java.io.File. "/tmp/shipyard-variant-thumbnails.png"))
        (s/select-option! driver ".import-files__file:has(select option:checked:text-is('Supported')) select" "Unsupported")
        (s/wait-visible! driver ".import-files:not([open])")
        (s/click! driver ".import-files summary")
        (is (s/wait-until #(= 3 (s/count-els driver ".import-file-thumbnail img"))))
        (is (= (set before)
               (set (s/js driver "() => [...document.querySelectorAll('.import-files__file')].map(el=>[el.querySelector('select').dataset.importFile,el.querySelector('img').getAttribute('src')])")))
            "Changing variant assignments preserves each file's image identity"))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (is (zero? (s/count-els driver ".import-file-thumbnail")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest archive-review-bulk-orientation-and-publication
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver)
        workspace (get-in started [:system :shipyard.workspace/db])
        cat (get-in started [:system :shipyard.catalog/db])
        lib (get-in started [:system :shipyard.library/index])
        directory (fs/create-temp-dir) zip (archives/archive! directory)
        invalid (archives/invalid-nested-archive! directory)
        session! #(importer/session! {:workspace workspace})
        before (catalog/listing! cat)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".import-start")
      (is (zero? (s/count-els driver ".import-start input, button:text-is('Review archive')")))
      (testing "canceling the desktop chooser retains the table and selection"
        (s/wait-visible! driver "[data-bulk-select]")
        (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
        (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
        (with-redefs [picker/choose! (fn [_ _] nil)]
          (let [response (.waitForResponse ^com.microsoft.playwright.Page (:page driver)
                                           "**/imports/choose"
                                           ^Runnable #(s/click! driver ".import-start button"))]
            (is (= 204 (.status response)))))
        (is (nil? (session!)))
        (is (= "1 selected" (s/text driver "[data-bulk-count]"))))
      (testing "an unavailable desktop keeps the table usable"
        (with-redefs [picker/choose! (fn [_ _] (throw (ex-info "Desktop unavailable" {})))]
          (.waitForResponse ^com.microsoft.playwright.Page (:page driver)
                            "**/imports/choose"
                            ^Runnable #(s/click! driver ".import-start button")))
        (s/wait-visible! driver "#import-status:text-is('Desktop unavailable')")
        (is (nil? (session!)))
        (is (= "1 selected" (s/text driver "[data-bulk-count]"))))
      (is (s/js driver "() => {const f=document.querySelector('.import-start');return f.method==='post' && f.getAttribute('action')===f.getAttribute('hx-post')}"))
      (testing "invalid nested ZIPs identify the source and leave the browser ready for another archive"
        (s/choose-path! driver ".import-start" invalid)
        (is (s/wait-until #(.contains (s/text driver "#import-status") "Cannot read ZIP Broken Fleet.zip → Download.zip → broken.zip")))
        (is (nil? (session!)))
        (is (= before (catalog/listing! cat))))
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (testing "an empty nested download is skipped and identified in review"
        (is (= "Skipped 1 empty nested ZIP file" (s/text driver ".import-warnings summary")))
        (s/click! driver ".import-warnings summary")
        (is (.contains (s/text driver ".import-warnings") "New Fleet.zip → Cruisers Supported(1).zip")))
      (is (s/js driver "() => [...document.querySelectorAll('.import-review form')].every(f=>f.method==='post' && f.getAttribute('action')===f.getAttribute('hx-post'))"))
      (testing "inferred versions share a row in the import browser and authoring stays unavailable"
        (is (s/wait-until #(= 2 (s/count-els driver ".bulk-orient__row"))))
        (is (= before (catalog/listing! cat)))
        (is (zero? (s/count-els driver "[data-detail-tab=regions]")))
        (is (zero? (s/count-els driver "[data-detail-tab=mounts]"))))
      (let [supported-id (first (for [[id entry] @(:entries (session!)) :when (= :supported (:variant entry))] id))
            group-id (get-in @(:entries (session!)) [supported-id :group])
            summary (str "[data-part-row='" group-id "'] .import-files summary")]
        (testing "each member's assignment is editable and a pair can be reversed"
          (doseq [[label variant] [["Unsupported" :unsupported] ["Supported" :supported]]]
            (s/click! driver summary)
            (s/select-option! driver (str "[data-import-file='" supported-id "']") label)
            (is (s/wait-until #(= variant (get-in @(:entries (session!)) [supported-id :variant]))))
            (is (s/wait-until #(not (s/js driver "() => document.querySelector('[data-import-group]').disabled"))))))
        (testing "a wrong inferred pair can be split, individually edited and manually regrouped"
          (s/click! driver summary)
          (s/click! driver (str "[data-import-split='" group-id "']"))
          (is (s/wait-until #(= 3 (s/count-els driver ".bulk-orient__row"))))
          (is (= "2 selected" (s/text driver "[data-bulk-count]")))
          (doseq [[label variant] [["Unsupported" :unsupported] ["Supported" :supported]]]
            (s/click! driver (str "[data-part-row='" supported-id "'] .import-files summary"))
            (s/select-option! driver (str "[data-import-file='" supported-id "']") label)
            (is (s/wait-until #(= variant (get-in @(:entries (session!)) [supported-id :variant]))))
            (is (s/wait-until #(not (s/js driver "() => document.querySelector('[data-import-group]').disabled")))))
          (s/fill-and-blur! driver "form[hx-post='/imports/group'] input[name=name]" "Hull")
          (s/click! driver "[data-import-group]")
          (is (s/wait-until #(= 2 (s/count-els driver ".bulk-orient__row"))))
          (is (= "0 selected" (s/text driver "[data-bulk-count]"))))
        (s/click! driver "[data-select-all=all]")
        (is (s/wait-until #(= "2 selected" (s/text driver "[data-bulk-count]"))))
        (s/select-option! driver ".part-bulk-edit select[name=field]" "Bundle / faction")
        (s/fill-and-blur! driver ".part-bulk-edit input[name=value]" "Reviewed Fleet")
        (s/click! driver "#part-bulk-apply")
        (is (s/wait-until #(every? (fn [p] (= "Reviewed Fleet" (:part/bundle p)))
                                   (catalog/browse (catalog/listing! (:catalog (session!))) {}))))
        (s/click! driver "[data-bulk-render-button]")
        (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count]))))
        (orient/set-yaw! driver 90)
        (testing "switching workspaces retains the import's dirty preview"
          (s/click! driver "[data-workspace-mode=ships]")
          (s/wait-visible! driver "#ship-results")
          (s/click! driver "[data-workspace-mode=browse]")
          (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :dirty])))))
        (orient/save! driver)
        (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
        (is (= before (catalog/listing! cat)))
        (s/click! driver "[data-bulk-back]")
        (s/wait-visible! driver ".import-review")
        (s/screenshot-el! driver ".layout" (java.io.File. "/tmp/shipyard-import-review.png"))
        (s/click! driver "form[hx-post='/imports/commit'] button")
        (s/wait-visible! driver ".import-start")
        (is (nil? (session!)))
        (is (fs/regular-file? zip))
        (doseq [path ["Reviewed Fleet/Cruiser/Hull/unsupported.stl"
                      "Reviewed Fleet/Cruiser/Hull/supported.stl"
                      "Reviewed Fleet/Cruiser/Prow/unsupported.stl"]]
          (is (fs/regular-file? (fs/path (index/root! lib) path))))
        (is (some? (:part/orientation (catalog/part (catalog/listing! cat) "Reviewed Fleet/Cruiser/Hull")))))
      (testing "a colliding import reports an error and can be canceled through the UI"
        (s/choose-path! driver ".import-start" zip)
        (s/wait-visible! driver ".import-review")
        (s/click! driver "[data-select-all=all]")
        (is (s/wait-until #(= "2 selected" (s/text driver "[data-bulk-count]"))))
        (s/select-option! driver ".part-bulk-edit select[name=field]" "Bundle / faction")
        (s/fill-and-blur! driver ".part-bulk-edit input[name=value]" "Reviewed Fleet")
        (s/click! driver "#part-bulk-apply")
        (is (s/wait-until #(every? (fn [p] (= "Reviewed Fleet" (:part/bundle p)))
                                   (catalog/browse (catalog/listing! (:catalog (session!))) {}))))
        (s/click! driver "form[hx-post='/imports/commit'] button")
        (is (s/wait-until #(.contains (s/text driver "#import-status") "Destination already exists")))
        (is (some? (session!)))
        (s/click! driver "form[hx-post='/imports/cancel'] button")
        (s/wait-visible! driver ".import-start")
        (is (nil? (session!))))
      (finally (s/quit! driver) (fixture/stop! started) (fs/delete-tree directory)))))

(deftest import-batches-and-select-all-matching
  (let [started (fixture/start! true) driver (s/make-driver)
        directory (fs/create-temp-dir) zip (fs/file directory "Large Fleet.zip")
        data (meshes/->binary-stl (meshes/cube))]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes
                            (for [n (range 60) folder ["Original Files" "Supported Files"]]
                              [(format "Cruiser/%s/Part %02d.stl" folder n) data]))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".import-start")
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (is (s/wait-until #(= 50 (s/count-els driver ".bulk-orient__row"))))
      (s/click! driver "[data-select-all=all]")
      (is (s/wait-until #(= "60 selected" (s/text driver "[data-bulk-count]"))))
      (s/scroll-into-view! driver "#bulk-orient-results .list-more")
      (is (s/wait-until #(= 60 (s/count-els driver ".bulk-orient__row"))))
      (is (= 60 (s/count-els driver "[data-bulk-select]:checked")))
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Part 5")
      (is (s/wait-until #(= 10 (s/count-els driver ".bulk-orient__row"))))
      (is (s/js driver "() => document.querySelector('#part-select-matching').checked"))
      (s/click! driver "[data-select-all=all]")
      (is (s/wait-until #(= "50 selected" (s/text driver "[data-bulk-count]"))))
      (is (zero? (s/count-els driver "[data-bulk-select]:checked")))
      (s/click! driver "[data-select-all=all]")
      (is (s/wait-until #(= "60 selected" (s/text driver "[data-bulk-count]"))))
      (s/click! driver "[data-select-all=none]")
      (is (s/wait-until #(= "0 selected" (s/text driver "[data-bulk-count]"))))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (is (fs/regular-file? zip))
      (finally (s/quit! driver) (fixture/stop! started) (fs/delete-tree directory)))))
