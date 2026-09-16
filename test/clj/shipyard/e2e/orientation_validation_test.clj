(ns shipyard.e2e.orientation-validation-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.catalog.db :as catalog]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.e2e.support :as s]
            [shipyard.part.orientation :as orientation]))

(deftest invalid-bulk-transport-preserves-every-durable-pose
  (let [started (fixture/start! true) driver (s/make-driver)
        c (:shipyard.catalog/db (:system started)) root (str (:root started))
        a (:prow fixture/ids) b (:bridge fixture/ids)
        q45 (orientation/from-euler-degrees 45 0 0) q90 (orientation/from-euler-degrees 90 0 0)]
    (try
      (doseq [id [a b]] (catalog/save-part-orientation! c id q45))
      (let [before (mapv #(slurp (sidecar/sidecar-file root %)) [a b])]
        (orient/open-grid! driver started [a b])
        (orient/set-yaw! driver 90)
        (s/js driver "() => { window.validationReplies=0; document.body.addEventListener('htmx:afterRequest', e => { if(e.detail.pathInfo.requestPath === '/orient/save') window.validationReplies++; }); }")
        (doseq [invalid [[0 0 0 0] [Double/NaN 0 0 1] [Double/POSITIVE_INFINITY 0 0 1] [1 2 3]]]
          (let [count-before (s/js driver "() => window.validationReplies")
                payload (pr-str {a [0 0 0 1] b invalid})]
            (s/js driver (str "() => { const corrupt = e => { if(e.detail.path === '/orient/save') { e.detail.parameters.orientations = "
                              (json/write-str payload)
                              "; document.body.removeEventListener('htmx:configRequest', corrupt); } }; document.body.addEventListener('htmx:configRequest', corrupt); }"))
            (orient/save! driver)
            (is (s/wait-until #(< count-before (s/js driver "() => window.validationReplies"))))
            (is (str/includes? (s/text driver "#bulk-orient-status") "data was invalid"))
            (is (= before (mapv #(slurp (sidecar/sidecar-file root %)) [a b])))
            (is (= 2 (get-in (s/stats driver) [:bulk :dirty])))
            (doseq [id [a b]]
              (is (saves/same-pose? q45 (orient/durable started id)))
              (is (saves/same-pose? q90 (orient/preview driver id))))))
        (let [fresh @(catalog/ingest! (catalog/browse (catalog/snapshot! c) {}) root)]
          (doseq [id [a b]]
            (is (saves/same-pose? q45 (:part/orientation (catalog/part fresh id))))))
        (orient/save! driver)
        (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
        (doseq [id [a b]] (is (saves/same-pose? q90 (orient/durable started id)))))
      (finally (s/quit! driver) (fixture/stop! started)))))
