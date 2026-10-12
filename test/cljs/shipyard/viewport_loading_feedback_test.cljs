(ns shipyard.viewport-loading-feedback-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [shipyard.viewport-loading :as loading]))

(deftest callbacks-retain-their-operation-without-suppressing-other-jobs
  (async done
         (let [stroke (loading/with-feedback! true #(loading/capture-feedback (fn [] loading/*brush-stroke?*)))
               ordinary (loading/capture-feedback (fn [] loading/*brush-stroke?*))]
           (-> (js/Promise.resolve)
               (.then (fn []
                        (is (true? (stroke)))
                        (is (false? (ordinary)))
                        (is (false? loading/*brush-stroke?*))
                        (try (loading/with-feedback! true #(throw (js/Error. "Stroke failed")))
                             (catch :default _ nil))
                        (is (false? loading/*brush-stroke?*))))
               (.finally done)))))
