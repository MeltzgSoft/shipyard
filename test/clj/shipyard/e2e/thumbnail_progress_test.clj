(ns shipyard.e2e.thumbnail-progress-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fixtures :as meshes]
            [shipyard.import-fixture :as archives]
            [shipyard.e2e.support :as s]
            [shipyard.thumbnail.cache :as cache]
            [shipyard.part-browser.thumbnail :as renderer])
  (:import [com.microsoft.playwright Page Route]
           [java.util.function Consumer]
           [java.util.concurrent CountDownLatch TimeUnit]))

(deftest progress-counts-background-work-instead-of-browser-loading
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        previews (get-in started [:system :shipyard.thumbnail/cache])
        held (atom []) release (CountDownLatch. 1) entered (CountDownLatch. 2)
        png (renderer/png! {:positions [0 0 0 1 0 0 0 1 0] :indices [0 1 2]} nil)
        idle "Thumbnail generation: 0 running · 0 queued"]
    (try
      (.route page "**/thumbnails/**"
              (reify Consumer (accept [_ route] (swap! held conj route))))
      (s/go! driver (s/base-url (:system started)))
      (is (s/wait-until #(do (s/js driver "() => true") (seq @held))))
      (is (s/wait-until #(= idle (s/text driver "[data-thumbnail-progress]")))
          "Requests waiting in the browser are not generation jobs")
      (doseq [id (range 3)]
        (cache/request! previews {:progress-test id}
                        #(do (.countDown entered) (.await release 20 TimeUnit/SECONDS) png)))
      (is (.await entered 5 TimeUnit/SECONDS))
      (is (s/wait-until #(= "Thumbnail generation: 2 running · 1 queued"
                            (s/text driver "[data-thumbnail-progress]"))))
      (.countDown release)
      (is (s/wait-until #(= idle (s/text driver "[data-thumbnail-progress]"))))
      (doseq [route @held] (.abort ^Route route))
      (s/wait-visible! driver ".part-thumbnail:text-is('Preview unavailable')")
      (is (= idle (s/text driver "[data-thumbnail-progress]")))
      (s/ship-table! driver)
      (is (s/wait-until #(= idle (s/text driver "[data-thumbnail-progress]"))))
      (finally (.countDown release) (s/quit! driver) (fixture/stop! started)))))

(deftest supported-import-thumbnails-and-variant-filter
  (let [started (fixture/start! true) driver (s/make-driver)
        zip (io/file (str (:temp started)) "Supported Fleet.zip")
        data (meshes/->binary-stl (meshes/cube))]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes
                            (concat (for [n (range 120)]
                                      [(format "Supported Files/Part %03d.stl" n) data])
                                    [["Original Files/Part 000.stl" data]
                                     ["Original Files/Standalone.stl" data]
                                     ["Unsupported Pitted/Pitted.stl" data]]))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".import-start")
      (s/wait-visible! driver ".bulk-orient__row")
      (is (zero? (s/count-els driver (str "[data-part-row='" (:supported fixture/ids) "']"))))
      (is (= 1 (s/count-els driver "#bulk-orient-filters select[name=variant]")))
      (is (= "" (s/js driver "() => document.querySelector('#bulk-orient-filters select[name=variant]').value")))
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (s/select-option! driver "#bulk-orient-filters select[name=variant]" "Supported")
      (s/wait-visible! driver ".results__count:text-is('120 matches')")
      (s/click! driver "#part-select-matching")
      (s/wait-visible! driver "[data-bulk-count]:text-is('120 selected')")
      (doseq [n [50 100 120]]
        (when (> n 50) (s/scroll-into-view! driver "#bulk-orient-results .list-more"))
        (is (s/wait-until #(= n (s/count-els driver ".bulk-orient__row"))))
        (s/wait-visible! driver ".part-thumbnail img"))
      (is (= 120 (s/count-els driver ".part-drawer__summary > .part-thumbnail[hx-get]")))
      (s/ship-table! driver)
      (s/click! driver "[data-workspace-mode=browse]")
      (s/wait-visible! driver ".import-review")
      (is (= "supported" (s/js driver "() => document.querySelector('#bulk-orient-filters select[name=variant]').value")))
      (is (s/wait-until #(= 120 (s/count-els driver ".bulk-orient__row"))))
      (s/select-option! driver "#bulk-orient-filters select[name=variant]" "Unsupported")
      (is (s/wait-until #(= 2 (s/count-els driver ".bulk-orient__row"))))
      (s/click! driver "#part-select-matching")
      (s/wait-visible! driver "[data-bulk-count]:text-is('121 selected')")
      (s/select-option! driver "#bulk-orient-filters select[name=variant]" "Unsupported (pitted)")
      (is (s/wait-until #(= 1 (s/count-els driver ".bulk-orient__row"))))
      (is (= "No preview" (s/text driver ".part-drawer__summary > .part-thumbnail")))
      (s/select-option! driver "#bulk-orient-filters select[name=variant]" "Supported")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Part 119")
      (is (s/wait-until #(and (= 1 (s/count-els driver ".bulk-orient__row"))
                              (= "Part 119" (s/text driver ".bulk-orient__part")))))
      (s/wait-visible! driver ".part-thumbnail img")
      (is (s/js driver "() => document.querySelector('.part-thumbnail img').naturalWidth === 128"))
      (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-supported-import.png"))
      (s/click! driver "form[hx-post='/imports/commit'] button")
      (s/wait-visible! driver ".import-start")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Part 119")
      (s/wait-visible! driver ".bulk-orient__empty")
      (is (zero? (s/count-els driver ".bulk-orient__row")))
      (.fill (.locator ^Page (:page driver) "#bulk-orient-filters input[name=q]") "")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Part 000")
      (is (s/wait-until #(= 1 (s/count-els driver ".bulk-orient__row")))
          (s/js driver "() => ({rows:document.querySelector('#bulk-orient-results').innerText, filters:[...document.querySelectorAll('#bulk-orient-filters input,#bulk-orient-filters select')].map(el=>[el.name,el.value])})"))
      (finally (s/quit! driver) (fixture/stop! started)))))
