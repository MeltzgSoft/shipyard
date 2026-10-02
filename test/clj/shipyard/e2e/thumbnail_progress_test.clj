(ns shipyard.e2e.thumbnail-progress-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
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
