(ns shipyard.e2e.part-thumbnail-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]))

(deftest region-thumbnails-and-summary-columns-follow-saved-edits
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        row (str "[data-part-row='" id "']")
        image (str row " .part-thumbnail img")
        image-src #(s/js driver (str "() => document.querySelector(\"" image "\").src"))
        saved #(get-in (catalog/part-context! cat id) [:part :part/paint-regions])]
    (try
      (s/go! driver (s/base-url sys))
      (s/scroll-into-view! driver row)
      (s/wait-visible! driver image)
      (is (= "Plug · turret ×1" (s/text driver (str row " .bulk-orient__mounts"))))
      (is (= "No" (s/text driver (str row " .bulk-orient__regions"))))
      (let [plain (image-src)]
        (s/open-part! driver "weapon")
        (s/await-part driver id)
        (s/click! driver "[data-detail-tab=regions]")
        (s/fill-and-blur! driver "#region-add input[name=name]" "Thumbnail trim")
        (s/click! driver "button:text-is('Add layer')")
        (s/wait-visible! driver "button[aria-label='Paint Thumbnail trim']")
        (s/click! driver "#region-fill button")
        (is (s/wait-until #(seq (:faces (saved)))))
        (s/click! driver "[data-part-back]")
        (s/scroll-into-view! driver row)
        (s/wait-visible! driver image)
        (is (= "Yes" (s/text driver (str row " .bulk-orient__regions"))))
        (is (not= plain (image-src)))
        (is (s/js driver (str "() => document.querySelector(\"" image "\").naturalWidth === 128")))
        (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-part-region-thumbnails.png"))
        (s/open-part! driver "weapon")
        (s/await-part driver id)
        (s/click! driver "[data-detail-tab=regions]")
        (s/click! driver "button[aria-label='Paint Primary']")
        (s/click! driver "#region-fill button")
        (is (s/wait-until #(empty? (:faces (saved)))))
        (s/click! driver "[data-part-back]")
        (s/scroll-into-view! driver row)
        (s/wait-visible! driver image)
        (is (= "No" (s/text driver (str row " .bulk-orient__regions"))))
        (is (= plain (image-src))))
      (finally (s/quit! driver) (fixture/stop! started)))))
