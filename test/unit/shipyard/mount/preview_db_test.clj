(ns shipyard.mount.preview-db-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.mount.preview-db :as drafts]))

(deftest draft-sequencing-is-monotonic-bounded-and-cancellation-survives-reordered-delivery
  (let [db {:state (atom {})}]
    (drafts/advance! db "draft" 1 false)
    (is (drafts/current?! db "draft" 1))
    (drafts/advance! db "draft" 3 true)
    (drafts/advance! db "draft" 2 false)
    (drafts/advance! db "draft" 3 false)
    (is (not (drafts/current?! db "draft" 1)))
    (is (not (drafts/current?! db "draft" 2)))
    (is (not (drafts/current?! db "draft" 3)))
    (drafts/advance! db "draft" 4 false)
    (is (drafts/current?! db "draft" 4))
    (doseq [n (range 129)] (drafts/advance! db (str n) 1 false))
    (is (= 128 (count @(:state db))))
    (is (not (drafts/current?! db "draft" 4)))))
