(ns shipyard.e2e.common-jobs-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.import-fixture :as archives]))

(deftest a-single-worker-recovers-from-backpressure-and-import-cancellation
  (let [started (fixture/start! true fixture/library! fixture/author!
                                {:shipyard.jobs/pool {:threads 1 :queue-size 8}})
        driver (s/make-driver) sys (:system started)
        zip (archives/archive! (:temp started))]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver ".part-thumbnail img")
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (is (s/wait-until #(= 2 (s/count-els driver ".part-thumbnail img"))))
      (is (s/wait-until #(= "Thumbnail generation: 0 running · 0 queued"
                            (s/text driver "[data-thumbnail-progress]"))))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (s/open-part! driver "hull")
      (s/await-part driver (:hull fixture/ids))
      (is (= "loaded" (:status (s/stats driver))))
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver ".part-thumbnail img")
      (is (s/wait-until #(s/js driver "() => [...document.querySelectorAll('.part-thumbnail img')].some(i => i.naturalWidth === 128)")))
      (is (not (.isShutdown ^java.util.concurrent.ThreadPoolExecutor (get-in sys [:shipyard.jobs/pool :pool]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
