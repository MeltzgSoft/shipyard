(ns shipyard.e2e.importer-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.library.index :as index]))

(deftest archive-review-bulk-orientation-and-publication
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver)
        workspace (get-in started [:system :shipyard.workspace/db])
        cat (get-in started [:system :shipyard.catalog/db])
        lib (get-in started [:system :shipyard.library/index])
        directory (fs/create-temp-dir) zip (archives/archive! directory)
        session! #(importer/session! {:workspace workspace})
        before (catalog/listing! cat)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".import-start")
      (s/fill-and-blur! driver ".import-start input[name=archive]" (str zip))
      (s/click! driver ".import-start button")
      (s/wait-visible! driver ".import-review")
      (testing "all archive entries replace the normal browser and authoring stays unavailable"
        (is (s/wait-until #(= 3 (s/count-els driver ".bulk-orient__row"))))
        (is (= before (catalog/listing! cat)))
        (is (zero? (s/count-els driver "[data-detail-tab=regions]")))
        (is (zero? (s/count-els driver "[data-detail-tab=mounts]"))))
      (let [supported-id (first (for [[id entry] @(:entries (session!)) :when (= :supported (:variant entry))] id))]
        (s/check! driver (str "[data-bulk-select][value='" supported-id "']"))
        (s/select-option! driver ".part-bulk-edit select[name=field]" "Supported / unsupported")
        (doseq [variant ["unsupported" "supported"]]
          (s/fill-and-blur! driver ".part-bulk-edit input[name=value]" variant)
          (s/click! driver ".part-bulk-edit button")
          (is (s/wait-until #(= (keyword variant) (get-in @(:entries (session!)) [supported-id :variant]))))
          (is (s/wait-until #(not (s/js driver "() => document.querySelector('.part-bulk-edit button').disabled")))))
        (s/click! driver "[data-import-select=all]")
        (is (s/wait-until #(= "3 selected" (s/text driver "[data-bulk-count]"))))
        (s/select-option! driver ".part-bulk-edit select[name=field]" "Bundle / faction")
        (s/fill-and-blur! driver ".part-bulk-edit input[name=value]" "Reviewed Fleet")
        (s/click! driver ".part-bulk-edit button")
        (is (s/wait-until #(every? (fn [p] (= "Reviewed Fleet" (:part/bundle p)))
                                   (vals (:parts (catalog/listing! (:catalog (session!))))))))
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
        (s/fill-and-blur! driver ".import-start input[name=archive]" (str zip))
        (s/click! driver ".import-start button")
        (s/wait-visible! driver ".import-review")
        (s/click! driver "[data-import-select=all]")
        (is (s/wait-until #(= "3 selected" (s/text driver "[data-bulk-count]"))))
        (s/select-option! driver ".part-bulk-edit select[name=field]" "Bundle / faction")
        (s/fill-and-blur! driver ".part-bulk-edit input[name=value]" "Reviewed Fleet")
        (s/click! driver ".part-bulk-edit button")
        (is (s/wait-until #(every? (fn [p] (= "Reviewed Fleet" (:part/bundle p)))
                                   (vals (:parts (catalog/listing! (:catalog (session!))))))))
        (s/click! driver "form[hx-post='/imports/commit'] button")
        (is (s/wait-until #(.contains (s/text driver "#import-status") "Destination already exists")))
        (is (some? (session!)))
        (s/click! driver "form[hx-post='/imports/cancel'] button")
        (s/wait-visible! driver ".import-start")
        (is (nil? (session!))))
      (finally (s/quit! driver) (fixture/stop! started) (fs/delete-tree directory)))))
