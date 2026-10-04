(ns shipyard.e2e.mount-face-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.fixtures :as fixtures]
            [shipyard.paint.faces :as faces]
            [shipyard.part.orientation :as orientation]))

(def id "Fleet/Cruiser/Large Mount Plate")
(def left-triangle [[-12 0 0] [-3 0 0] [-12 40 0]])
(def triangles
  [left-triangle
   [[3 0 0] [12 0 0] [3 40 0]]
   [[-14 19 0] [-13 19 0] [-14 21 0]]
   [[-3 0 0] [-3 40 0] [-12 40 0]]
   [[12 0 0] [12 40 0] [3 40 0]]
   [[13 19 0] [14 19 0] [13 21 0]]])

(defn build-library! [root]
  (let [file (io/file (str root) id "unsupported.stl")]
    (fs/create-dirs (.getParentFile file))
    (with-open [out (io/output-stream file)] (.write out ^bytes (fixtures/->binary-stl triangles)))
    root))

(deftest saves-and-edits-exact-large-faces-with-a-mirrored-partner
  (s/assert-bundle!)
  (let [started (fixture/start! true build-library! (fn [cat] (catalog/save-part-orientation! cat id (orientation/from-euler-degrees 0 0 180))))
        sys (:system started) cat (:shipyard.catalog/db sys) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys)) (s/open-part! driver "Large Mount Plate") (s/await-part driver id)
      (s/click! driver "[data-detail-tab=mounts]")
      (let [target (first (filter #(= (faces/face-key left-triangle) (:key %)) (:region-faces (s/stats driver))))
            bounds (s/bounds driver "#viewport")]
        (s/click-point! driver (+ (:x bounds) (:x target)) (+ (:y bounds) (:y target))))
      (s/wait-visible! driver ".mount-wizard__form")
      (s/select-option! driver ".mount-wizard__form select[name=kind]" "socket")
      (s/check! driver ".mount-wizard__form input[name=mirror]")
      (s/fill-and-blur! driver ".mount-wizard__form input[name=capacity]" "3")
      (let [picked (:facet-indices (:preview (s/stats driver)))]
        (is (= 2 (count picked)))
        (s/click! driver ".mount-wizard__actions button[value=create]")
        (is (s/wait-until #(= 2 (get-in (s/stats driver) [:interfaces :count]))))
        (let [items (get-in (s/stats driver) [:interfaces :items])
              boxes (sort-by #(get-in % [0 0]) (map :face-bounds items))
              [[left-min left-max] [right-min right-max]] boxes]
          (is (= [2 2] (mapv :triangles items)))
          (is (every? #(< (abs %) 1e-6) (map - [-12 0] (subvec left-min 0 2))))
          (is (every? #(< (abs %) 1e-6) (map - [-3 40] (subvec left-max 0 2))))
          (is (every? #(< (abs %) 1e-6) (map - [3 0] (subvec right-min 0 2))))
          (is (every? #(< (abs %) 1e-6) (map - [12 40] (subvec right-max 0 2))))
          (is (= [2 2] (mapv #(count (:split-lines %)) items))))
        (let [mounts (:part/mounts (:part (catalog/part-context! cat id)))
              base (first (filter #(= :picked (:mount/origin %)) mounts))
              stable (select-keys base [:mount/pos :mount/axis :mount/roll :mount/split])]
          (is (= picked (get-in base [:mount/facet :indices])))
          (s/click! driver (str "form:has(input[name=mount-id][value='" (name (:mount/id base)) "']) button:has-text('Edit')"))
          (s/wait-visible! driver ".mount-wizard__form")
          (is (s/wait-until #(= picked (get-in (s/stats driver) [:preview :facet-indices]))))
          (s/click! driver ".mount-wizard__actions button[value=update]")
          (is (s/wait-until #(nil? (:preview (s/stats driver)))))
          (is (= stable (select-keys (first (:part/mounts (:part (catalog/part-context! cat id)))) (keys stable)))))
        (s/go! driver (s/base-url sys))
        (s/open-part! driver "Large Mount Plate") (s/await-part driver id)
        (is (= [2 2] (mapv :triangles (get-in (s/stats driver) [:interfaces :items]))))
        (s/screenshot-el! driver "#viewport" (io/file "/tmp/shipyard-saved-mount-faces.png")))
      (finally (s/quit! driver) (fixture/stop! started)))))
