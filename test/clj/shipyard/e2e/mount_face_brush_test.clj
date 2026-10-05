(ns shipyard.e2e.mount-face-brush-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.mount-face-test :as mount-faces]
            [shipyard.fixtures :as fixtures]
            [shipyard.paint.faces :as faces])
  (:import [java.nio.file Files] [java.util Arrays]))

(defn- face-point
  ([driver] (face-point driver 0))
  ([driver triangle]
   (let [key (faces/face-key (nth (fixtures/cube 1.0) triangle))
         face (first (filter #(= key (:key %)) (:region-faces (s/stats driver))))
         box (s/bounds driver "#viewport")]
     [(+ (:x box) (:x face)) (+ (:y box) (:y face))])))

(deftest erase-undo-reset-save-and-reopen-a-recess-selection
  (s/assert-bundle!)
  (let [started (fixture/start! true fixture/library! (fn [_])) sys (:system started)
        driver (s/make-driver) id (:weapon fixture/ids) cat (:shipyard.catalog/db sys)
        file (io/file (str (:root started)) id "unsupported.stl") original (Files/readAllBytes (.toPath file))]
    (try
      (s/go! driver (s/base-url sys)) (s/open-prepared-part! driver sys "weapon" id)
      (s/click! driver "[data-detail-tab=mounts]")
      (apply s/click-point! driver (face-point driver))
      (s/wait-visible! driver ".mount-wizard__form")
      (let [picked (get-in (s/stats driver) [:preview :facet-indices])
            frame (select-keys (:preview (s/stats driver)) [:position :axis :roll])]
        (is (= 2 (count picked)))
        (s/check! driver "[data-mount-face-edit]")
        (is (s/wait-until #(= 8 (get-in (s/stats driver) [:preview :geometries]))))
        (s/select-option! driver "select[name=alignment-axis]" "Vertical (+Y)")
        (is (s/wait-until #(= "vertical" (get-in (s/stats driver) [:preview :alignment-axis]))))
        (let [[x y] (face-point driver)] (s/drag! driver [x y] [(+ x 1) y]))
        (is (s/wait-until #(= 1 (get-in (s/stats driver) [:preview :triangles]))))
        (is (empty? (:part/mounts (:part (catalog/part-context! cat id)))))
        (let [[x y] (face-point driver 1)] (s/drag! driver [x y] [(+ x 1) y]))
        (is (s/wait-until #(zero? (get-in (s/stats driver) [:preview :triangles]))))
        (is (s/js driver "() => document.querySelector('.mount-wizard__actions button[value=create]').disabled"))
        (s/click! driver "[data-mount-faces-undo]")
        (is (s/wait-until #(= 1 (get-in (s/stats driver) [:preview :triangles]))))
        (s/click! driver "[data-mount-faces-undo]")
        (is (s/wait-until #(= picked (get-in (s/stats driver) [:preview :facet-indices]))))
        (let [[x y] (face-point driver)] (s/drag! driver [x y] [(+ x 1) y]))
        (s/click! driver "[data-mount-faces-reset]")
        (is (s/wait-until #(= picked (get-in (s/stats driver) [:preview :facet-indices]))))
        (let [camera (:camera (s/stats driver)) [x y] (face-point driver)]
          (.down (.keyboard (:page driver)) "Alt")
          (try (s/drag! driver [x y] [(+ x 20) y]) (finally (.up (.keyboard (:page driver)) "Alt")))
          (is (s/wait-until #(not= camera (:camera (s/stats driver)))))
          (is (= picked (get-in (s/stats driver) [:preview :facet-indices]))))
        (let [[x y] (face-point driver)] (s/drag! driver [x y] [(+ x 1) y]))
        (is (s/wait-until #(= 1 (get-in (s/stats driver) [:preview :triangles]))))
        (is (= "vertical" (get-in (s/stats driver) [:preview :alignment-axis])))
        (is (= frame (select-keys (:preview (s/stats driver)) (keys frame))))
        (s/check! driver "[name=create-pitted]")
        (s/select-option! driver "select[name=cut-kind]" "Recess")
        (s/fill-and-blur! driver "[name=cut-depth]" "0.1")
        (s/fill-and-blur! driver "[name=cut-border]" "0.05")
        (s/screenshot-el! driver "#detail" (io/file "/tmp/shipyard-mount-face-brush.png"))
        (let [trimmed (get-in (s/stats driver) [:preview :facet-indices])]
          (s/click! driver ".mount-wizard__actions button[value=create]")
          (is (s/wait-until #(nil? (:preview (s/stats driver)))))
          (let [mount (first (:part/mounts (:part (catalog/part-context! cat id))))]
            (is (= trimmed (get-in mount [:mount/facet :indices])))
            (is (= :vertical (:mount/alignment-axis mount)))
            (is (= 3 (count (first (:mount/outline mount)))))
            (is (= :recess (get-in mount [:mount/cut :kind])))
            (is (.isFile (io/file (.getParentFile file) "unsupported-pitted.stl")))
            (is (Arrays/equals ^bytes original ^bytes (Files/readAllBytes (.toPath file))))
            (s/click! driver (str "form:has(input[name=mount-id][value='" (name (:mount/id mount)) "']) button:has-text('Edit')"))
            (s/wait-visible! driver ".mount-wizard__form")
            (is (s/wait-until #(= trimmed (get-in (s/stats driver) [:preview :facet-indices]))))
            (is (= frame (select-keys (:preview (s/stats driver)) (keys frame)))))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest trimming-a-pair-reflects-only-the-retained-original-faces
  (s/assert-bundle!)
  (let [started (fixture/start! true mount-faces/build-library! (fn [_]))
        sys (:system started) driver (s/make-driver) id mount-faces/id
        cat (:shipyard.catalog/db sys)]
    (try
      (s/go! driver (s/base-url sys)) (s/open-prepared-part! driver sys "Large Mount Plate" id)
      (s/click! driver "[data-detail-tab=mounts]")
      (let [face (first (filter #(= (faces/face-key mount-faces/left-triangle) (:key %))
                                (:region-faces (s/stats driver))))
            box (s/bounds driver "#viewport") x (+ (:x box) (:x face)) y (+ (:y box) (:y face))]
        (s/click-point! driver x y) (s/wait-visible! driver ".mount-wizard__form")
        (s/select-option! driver ".mount-wizard__form select[name=kind]" "socket")
        (s/select-option! driver ".mount-wizard__form select[name=alignment-axis]" "Vertical (+Y)")
        (s/check! driver ".mount-wizard__form input[name=mirror]")
        (s/select-option! driver ".mount-wizard__form select[name=alignment-axis]" "Horizontal (+X)")
        (is (s/wait-until #(= 2 (count (get-in (s/stats driver) [:preview :alignment-lines])))))
        (s/check! driver "[data-mount-face-edit]")
        (s/drag! driver [x y] [(+ x 1) y]))
      (is (s/wait-until #(= 1 (get-in (s/stats driver) [:preview :triangles]))))
      (let [trimmed (get-in (s/stats driver) [:preview :facet-indices])]
        (s/click! driver ".mount-wizard__actions button[value=create]")
        (is (s/wait-until #(= [1 1] (mapv :triangles (get-in (s/stats driver) [:interfaces :items])))))
        (let [items (get-in (s/stats driver) [:interfaces :items])]
          (is (= [trimmed trimmed] (mapv :facet-indices items)))
          (is (= [[-12 0] [3 0]] (mapv #(subvec (first (:face-bounds %)) 0 2) items))))
        (let [before (:part/mounts (:part (catalog/part-context! cat id))) base (first before)]
          (is (= [:horizontal :horizontal] (mapv :mount/alignment-axis before)))
          (s/click! driver (str "form:has(input[name=mount-id][value='" (name (:mount/id base)) "']) button:has-text('Edit')"))
          (s/wait-visible! driver ".mount-wizard__form")
          (is (s/wait-until #(= trimmed (get-in (s/stats driver) [:preview :facet-indices]))))
          (let [face (first (filter #(= (faces/face-key (nth mount-faces/triangles 3)) (:key %))
                                    (:region-faces (s/stats driver))))
                box (s/bounds driver "#viewport") x (+ (:x box) (:x face)) y (+ (:y box) (:y face))]
            (s/check! driver "[data-mount-face-edit]")
            (s/drag! driver [x y] [(+ x 1) y])
            (is (s/wait-until #(zero? (get-in (s/stats driver) [:preview :triangles]))))
            (s/click! driver ".mount-wizard__actions .detail__dismiss")
            (is (s/wait-until #(nil? (:preview (s/stats driver)))))
            ;; Detail responses may backfill the mirror's derived cache IDs.
            ;; Compare every authored field, including the original's saved IDs.
            (let [authored (fn [mounts] (mapv #(cond-> % (= :mirrored (:mount/origin %))
                                                       (dissoc :mount/facet)) mounts))]
              (is (= (authored before) (authored (:part/mounts (:part (catalog/part-context! cat id))))))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
