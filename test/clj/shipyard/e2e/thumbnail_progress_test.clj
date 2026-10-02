(ns shipyard.e2e.thumbnail-progress-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fixtures :as meshes]
            [shipyard.import-fixture :as archives]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright Page Route]
           [java.util.function Consumer]))

(deftest progress-includes-pending-ready-and-unavailable-thumbnails
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        held (atom nil) first? (atom true)]
    (try
      (.route page "**/thumbnails/**"
              (reify Consumer
                (accept [_ route]
                  (if (compare-and-set! first? true false)
                    (reset! held route)
                    (.resume ^Route route)))))
      (s/go! driver (s/base-url (:system started)))
      (is (s/wait-until #(do (s/js driver "() => true") (some? @held))))
      (is (s/wait-until #(re-find #"[1-9][0-9]* generating" (s/text driver "[data-thumbnail-progress]"))))
      (.abort ^Route @held)
      (is (s/wait-until #(.contains (s/text driver "[data-thumbnail-progress]") "1 unavailable")))
      (is (s/wait-until #(re-find #"[1-9][0-9]* ready" (s/text driver "[data-thumbnail-progress]"))))
      (is (s/wait-until #(.contains (s/text driver "[data-thumbnail-progress]") "0 generating")))
      (is (.contains (s/text driver "[data-thumbnail-progress]") "1 no preview"))
      (is (s/js driver "() => document.querySelector('.part-thumbnail:not([hx-get])').title.includes('supported STL')"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest supported-only-import-rows-are-counted-across-batches
  (let [started (fixture/start! true) driver (s/make-driver)
        zip (io/file (str (:temp started)) "Supported Fleet.zip")
        data (meshes/->binary-stl (meshes/cube))]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes
                            (for [n (range 120)]
                              [(format "Supported Files/Part %03d.stl" n) data]))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".import-start")
      (s/choose-path! driver ".import-start" zip)
      (s/click! driver ".import-start button[type=submit]")
      (s/wait-visible! driver ".import-review")
      (doseq [n [50 100 120]]
        (when (> n 50) (s/scroll-into-view! driver "#bulk-orient-results .list-more"))
        (is (s/wait-until #(= n (s/count-els driver ".bulk-orient__row"))))
        (is (s/wait-until #(.contains (s/text driver "[data-thumbnail-progress]") (str n " no preview")))))
      (is (.contains (s/text driver "[data-thumbnail-progress]") "0 ready · 0 generating · 0 waiting"))
      (is (zero? (s/count-els driver ".part-thumbnail[hx-get]")))
      (is (s/js driver "() => [...document.querySelectorAll('.part-thumbnail')].every(el => el.title.includes('supported STL'))"))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (finally (s/quit! driver) (fixture/stop! started)))))
