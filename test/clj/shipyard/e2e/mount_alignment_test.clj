(ns shipyard.e2e.mount-alignment-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.geom :as geom]
            [shipyard.math :as math]
            [shipyard.persistence-fixture :as persisted]))

(defn- edit-axis! [driver sys part-name id mount-id axis]
  (s/open-prepared-part! driver sys part-name id)
  (s/click! driver "[data-detail-tab=mounts]")
  (s/click! driver (str "form:has(input[name=mount-id][value='" mount-id "']) button:text-is('Edit')"))
  (s/wait-visible! driver ".mount-wizard__form")
  (s/select-option! driver "select[name=alignment-axis]" ({"none" "None" "horizontal" "Horizontal (+X)" "horizontal-negative" "Horizontal (−X)"
                                                           "vertical" "Vertical (+Y)" "vertical-negative" "Vertical (−Y)"} axis))
  (is (s/wait-until #(= (when-not (= "none" axis) axis) (get-in (s/stats driver) [:preview :alignment-axis]))))
  (is (= (if (= "none" axis) 0 1) (count (get-in (s/stats driver) [:preview :alignment-lines]))))
  (when-not (= "none" axis)
    (let [[a b] (first (get-in (s/stats driver) [:preview :alignment-lines]))
          reference (get-in (s/stats driver) [:preview (if (#{"horizontal" "horizontal-negative"} axis) :roll :up)])]
      (is (< (abs (- (if (#{"horizontal-negative" "vertical-negative"} axis) -1.0 1.0)
                     (math/dot (math/normalize (math/subtract b a)) reference))) 1e-6))))
  (s/click! driver ".mount-wizard__actions button[value=update]")
  (is (s/wait-until #(nil? (:preview (s/stats driver))))))

(defn- slot [driver path]
  (first (filter #(= (pr-str path) (pr-str (mapv (fn [[m i]] [(keyword m) i]) (:slot %))))
                 (get-in (s/stats driver) [:assembly :slots]))))

(defn- assign! [driver path part-name]
  (let [drawer (str ".assembly__slot[data-slot='" (pr-str path) "']")]
    (is (s/wait-until #(zero? (s/count-els driver ".assembly__preparation, #detail .htmx-request"))))
    (when-not (s/js driver (str "() => document.querySelector(" (pr-str drawer) ").open"))
      (s/click! driver (str drawer " > summary")))
    (s/click! driver (str drawer " button[name=part-id]:has(.assembly__candidate-name:text-is('" part-name "'))"))))

(deftest authored-axes-rotate-a-battery-and-its-descendants
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys)]
    (try
      (s/go! driver (s/base-url sys))
      (testing "both mating mounts can be edited through the actual authoring UI"
        (edit-axis! driver sys "hull" (:hull fixture/ids) "weapon" "horizontal")
        (let [preview-count #(count (get-in (s/stats driver) [:interfaces :alignment-lines]))]
          (is (s/wait-until #(pos? (preview-count)))))
        (edit-axis! driver sys "weapon" (:weapon fixture/ids) "plug" "vertical")
        (is (= :vertical (:mount/alignment-axis (first (filter (comp #{:plug} :mount/kind) (get-in (persisted/catalog! cat) [:parts (:weapon fixture/ids) :part/mounts])))))))
      (testing "a battery quarter turn is visible in the scene and inherited by its turret"
        (s/open-assembly! driver)
        (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
        (s/click! driver ".assembly__hull button")
        (s/await-assembly-prepared! driver 1)
        (assign! driver [[:weapon 0]] "weapon")
        (s/await-assembly-prepared! driver 2)
        (assign! driver [[:weapon 0] [:turret 0]] "turret")
        (s/await-assembly-prepared! driver 3)
        (let [battery (:matrix (slot driver [[:weapon 0]])) turret (:matrix (slot driver [[:weapon 0] [:turret 0]]))
              direction (fn [m v] (math/subtract (geom/transform-point m v) (geom/transform-point m [0.0 0.0 0.0])))
              up (direction battery [0.0 1.0 0.0])]
          (is (every? #(< (abs %) 1e-6) (map - [-1.0 0.0 0.0] up)))
          (is (every? #(< (abs %) 1e-6) (map - up (direction turret [0.0 1.0 0.0]))))
          (is (every? #(< (abs %) 1e-6)
                      (map - [-3.0 0.0 2.0] (geom/transform-point battery [0.0 0.0 -0.5]))))))
      (s/fill-and-blur! driver ".assembly__save input[name=name]" "Aligned battery")
      (s/click! driver ".assembly__save button")
      (s/wait-visible! driver "[role=status]:has-text('Class saved.')")
      (testing "reversing the selected direction reverses the battery and its turret"
        (s/click! driver "[data-workspace-mode=browse]")
        (s/wait-visible! driver "[data-part-back], .bulk-orient__row")
        (edit-axis! driver sys "weapon" (:weapon fixture/ids) "plug" "vertical-negative")
        (s/open-class! driver "Aligned battery")
        (s/await-assembly-prepared! driver 3)
        (doseq [path [[[:weapon 0]] [[:weapon 0] [:turret 0]]]]
          (let [m (:matrix (slot driver path))]
            (is (every? #(< (abs %) 1e-6)
                        (map - [1.0 0.0 0.0]
                             (math/subtract (geom/transform-point m [0.0 1.0 0.0]) (geom/transform-point m [0.0 0.0 0.0]))))))))
      (testing "opposite horizontal arrows require a half turn"
        (s/click! driver "[data-workspace-mode=browse]")
        (s/wait-visible! driver "[data-part-back], .bulk-orient__row")
        (edit-axis! driver sys "weapon" (:weapon fixture/ids) "plug" "horizontal-negative")
        (s/open-class! driver "Aligned battery")
        (s/await-assembly-prepared! driver 3)
        (let [m (:matrix (slot driver [[:weapon 0]]))]
          (is (every? #(< (abs %) 1e-6)
                      (map - [0.0 -1.0 0.0]
                           (math/subtract (geom/transform-point m [0.0 1.0 0.0]) (geom/transform-point m [0.0 0.0 0.0])))))))
      (testing "clearing one axis removes the line and restores the unrotated placement"
        (s/click! driver "[data-workspace-mode=browse]")
        (s/wait-visible! driver "[data-part-back], .bulk-orient__row")
        (edit-axis! driver sys "weapon" (:weapon fixture/ids) "plug" "none")
        (is (not (contains? (first (filter (comp #{:plug} :mount/kind) (:part/mounts (catalog/part (catalog/snapshot! cat) (:weapon fixture/ids))))) :mount/alignment-axis)))
        (s/open-class! driver "Aligned battery")
        (s/await-assembly-prepared! driver 3)
        (let [battery (:matrix (slot driver [[:weapon 0]]))]
          (is (every? #(< (abs %) 1e-6)
                      (map - [0.0 1.0 0.0]
                           (math/subtract (geom/transform-point battery [0.0 1.0 0.0]) (geom/transform-point battery [0.0 0.0 0.0])))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
