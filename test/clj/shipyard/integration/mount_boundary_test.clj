(ns shipyard.integration.mount-boundary-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.mount-boundary-fixture :as parts])
  (:import [java.nio.file Files]))

(deftest untrimmed-selections-save-without-requiring-a-cut-outline
  (let [started (fixture/start! false parts/build! (fn [_]))
        sys (:system started) handler (:handler started) cat (:shipyard.catalog/db sys)
        library (:shipyard.library/index sys) mesh-cache (:shipyard.mesh/cache sys)
        post! (fn [uri params] (handler (mock/request :post uri params)))]
    (try
      (doseq [[id count-expected outline?] [[parts/thin-id 4 true] [parts/duplicate-id 8 false]]]
        (let [source (index/fresh-source-file! library id)
              before (Files/readAllBytes (.toPath (io/file source)))
              key (:mesh-key (cache/ensure! mesh-cache source))
              _ (index/record-mesh-key! library id key nil)
              response (post! "/facet" {:part-id id :mesh-key key :triangle-index "0"})
              event (edn/read-string (get (json/read-str (get-in response [:headers "HX-Trigger"])) "shipyard:facet-preview"))
              indices (:facet-indices event)
              form {:part-id id :mesh-key key :mount-id "seat" :kind "plug" :action "create"
                    :frame (pr-str (:frame event)) :facet-indices (pr-str indices)
                    :face-seed "0" :face-angle "1" :face-seam "0.005"}]
          (testing "complete, untrimmed selections are valid durable mounts"
            (is (= 200 (:status response)))
            (is (= count-expected (count indices)))
            (is (= 200 (:status (post! "/mounts" form))))
            (let [saved (first (:part/mounts (:part (catalog/part-context! cat id))))]
              (is (= indices (get-in saved [:mount/facet :indices])))
              (is (= outline? (boolean (seq (:mount/outline saved)))))))
          (when-not outline?
            (testing "an unsupported physical cut still fails with actionable feedback"
              (let [response (post! "/mounts" (assoc form :action "update" :original-mount-id "seat" :create-pitted "on"
                                                     :cut-kind "recess" :cut-depth "0.1" :cut-border "0.1"))]
                (is (= 422 (:status response)))
                (is (str/includes? (:body response) "save the mount without a cut"))
                (is (= 1 (count (:part/mounts (:part (catalog/part-context! cat id)))))))))
          (is (java.util.Arrays/equals before (Files/readAllBytes (.toPath (io/file source)))))))
      (finally (fixture/stop! started)))))
