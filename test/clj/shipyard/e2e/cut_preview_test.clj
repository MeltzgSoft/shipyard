(ns shipyard.e2e.cut-preview-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.fixtures :as meshes]
            [shipyard.jobs :as jobs])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def id "Fleet/Cruiser/Prepared Cut Plate")

(defn triangles [n]
  (vec (mapcat (fn [[x y]] [[[x y 0.0] [(inc x) y 0.0] [x (inc y) 0.0]]
                            [[(inc x) y 0.0] [(inc x) (inc y) 0.0] [x (inc y) 0.0]]])
               (for [x (range n) y (range n)] [x y]))))

(defn library! [root]
  (let [file (io/file (str root) id "unsupported.stl")]
    (fs/create-dirs (.getParentFile file))
    (with-open [out (io/output-stream file)]
      (.write out ^bytes (meshes/->binary-stl (triangles 45))))
    root))

(deftest substantial-draft-remains-editable-and-cancel-discards-late-preparation
  (s/assert-bundle!)
  (let [started (fixture/start! true library! (fn [_])) sys (:system started)
        driver (s/make-driver) scope (jobs/scope! (:shipyard.jobs/pool sys))
        entered (CountDownLatch. 2) release (CountDownLatch. 1)]
    (try
      (s/go! driver (s/base-url sys)) (s/open-prepared-part! driver sys "Prepared Cut Plate" id)
      (s/click! driver "[data-detail-tab=mounts]")
      (let [{:keys [x y width height]} (s/bounds driver "#viewport")]
        (s/click-point! driver (+ x (/ width 2)) (+ y (/ height 2))))
      (s/wait-visible! driver ".mount-wizard__form")
      (is (= 4050 (get-in (s/stats driver) [:preview :triangles])))
      (is (:accepted? (jobs/submit-batch! scope
                                          (mapv (fn [key] {:key key :run! #(do (.countDown entered) (.await release))}) [0 1]))))
      (is (.await entered 5 TimeUnit/SECONDS))
      (s/check! driver "[name=create-pitted]")
      (is (s/wait-until #(pos? (:queued (jobs/progress! (:scope (:shipyard.preparation/service sys)))))))
      (testing "input and animation frames continue while derived geometry is queued"
        (is (true? (s/js driver "async () => {let frames=0; await new Promise(resolve=>{function tick(){if(++frames===3)resolve();else requestAnimationFrame(tick)} requestAnimationFrame(tick)});const field=document.querySelector('[name=cut-depth]');field.value='0.3';field.dispatchEvent(new Event('input',{bubbles:true}));return frames===3 && field.value==='0.3'}")))
        (s/click! driver ".mount-wizard__actions button:text-is('Cancel')")
        (is (s/wait-until #(nil? (:preview (s/stats driver)))))
        (.countDown release)
        (is (s/wait-until #(= {:running 0 :queued 0} (jobs/progress! scope))))
        (is (s/wait-until #(= {:running 0 :queued 0} (jobs/progress! (:scope (:shipyard.preparation/service sys))))))
        (is (nil? (:preview (s/stats driver))))
        (is (empty? (:part/mounts (:part (catalog/part-context! (:shipyard.catalog/db sys) id))))))
      (finally (.countDown release) (jobs/close! scope) (s/quit! driver) (fixture/stop! started)))))
