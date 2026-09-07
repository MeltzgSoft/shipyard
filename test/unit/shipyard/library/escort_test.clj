(ns shipyard.library.escort-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.library.escort :as escort]
            [shipyard.mesh.stl :as stl]))

(defn- transform [tris [sx sy sz] [dx dy dz]]
  (mapv (fn [tri]
          (mapv (fn [[x y z]]
                  [(+ dx (* sx x)) (+ dy (* sy y)) (+ dz (* sz z))])
                tri))
        tris))

(defn- mesh [tris]
  (stl/parse-bytes (f/->binary-stl tris)))

(defn- box [scale offset]
  (mesh (transform (f/cube 2.0) scale offset)))

(defn- m [{:keys [length cross-section volume component-count component-volumes profile]}]
  {:length length
   :cross-section cross-section
   :volume volume
   :component-count (or component-count 1)
   :component-volumes component-volumes
   :profile profile})

(deftest measure-test
  (testing "computes sorted extents, volume, components and axial profile"
    (let [one (box [30.0 1.0 0.5] [0.0 0.0 0.0])
          two (box [1.0 1.0 1.0] [100.0 0.0 0.0])
          combined (mesh (concat (transform (f/cube 2.0) [30.0 1.0 0.5] [0.0 0.0 0.0])
                                 (transform (f/cube 2.0) [1.0 1.0 1.0] [100.0 0.0 0.0])))
          measured (escort/measure one)
          multi (escort/measure combined)]
      (is (= [1.0 2.0 60.0] (:sorted-extents measured)))
      (is (= 1 (:component-count measured)))
      (is (> (:volume measured) (:volume (escort/measure two))))
      (is (= 2 (:component-count multi)))
      (is (= 2 (count (:component-volumes multi))))
      (is (seq (:profile measured))))))

(deftest variant-family?-test
  (testing "uses sibling-relative cross-section and profile overlap"
    (let [a (m {:length 60.0 :cross-section [19.0 28.0] :volume 1200.0
                :profile (zipmap (range 120) (repeat 1.0))})
          b (m {:length 72.0 :cross-section [19.2 28.1] :volume 1400.0
                :profile (zipmap (range 130) (repeat 1.0))})
          kit (m {:length 18.0 :cross-section [19.1 28.0] :volume 5000.0
                  :profile (zipmap (range 21) (repeat 1.0))})]
      (is (escort/variant-family? a b))
      (is (not (escort/variant-family? a kit)))
      (is (not (escort/variant-family? a (assoc b :cross-section [10.0 28.0])))))))

(deftest classify-measurement-test
  (let [ship (m {:length 60.0 :cross-section [19.0 28.0] :volume 1200.0
                 :profile (zipmap (range 120) (repeat 1.0))})
        ship-sibling (assoc ship :length 70.0 :volume 1300.0)
        component (m {:length 10.0 :cross-section [5.0 6.0] :volume 6000.0
                      :profile (zipmap (range 20) (repeat 1.0))})
        anchor (m {:length 40.0 :cross-section [9.0 10.0] :volume 13000.0
                   :profile (zipmap (range 80) (repeat 1.0))})
        assembly (m {:length 50.0 :cross-section [10.0 12.0] :volume 19000.0
                     :component-count 2 :component-volumes [6000.0 13000.0]
                     :profile (zipmap (range 100) (repeat 1.0))})]
    (testing "whole ships come from family similarity, not absolute size"
      (is (= :whole-ship
             (:escort/classification (escort/classify-measurement ship [ship-sibling])))))
    (testing "components require a plausible larger sibling anchor"
      (is (= :kitbash-component
             (:escort/classification (escort/classify-measurement component [anchor])))))
    (testing "assemblies decompose into sibling component volumes"
      (is (= :kitbash-assembly
             (:escort/classification (escort/classify-measurement assembly [component anchor])))))
    (testing "uncertain cases remain visible"
      (is (= :unresolved
             (:escort/classification (escort/classify-measurement component [])))))))

(deftest classify-library-test
  (let [a {:part/id "bundle/escort/a"
           :part/name "A"
           :part/bundle "bundle"
           :part/class "Escort"
           :part/role-hint :unknown}
        b (assoc a :part/id "bundle/escort/b" :part/name "B")
        ignored (assoc a :part/id "bundle/cruiser/c" :part/class "Cruiser")
        ship (m {:length 60.0
                 :cross-section [19.0 28.0]
                 :volume 1200.0
                 :profile (zipmap (range 120) (repeat 1.0))})
        rows (escort/classify-library [a b ignored]
                                      {(:part/id a) ship
                                       (:part/id b) (assoc ship :length 70.0)})]
    (testing "classification is a pure transformation over parts and measurements"
      (is (= #{"bundle/escort/a" "bundle/escort/b"}
             (set (map :part/id rows))))
      (is (every? #(= :whole-ship
                      (get-in % [:classification :escort/classification]))
                  rows))
      (is (every? #(= :ship (get-in % [:part :part/role-hint])) rows)))))

(deftest apply-classification-test
  (testing "whole ships become geometry-sourced role hints"
    (let [part {:part/id "Bundle/Escort/Cyanide Prow Python" :part/role-hint :unknown}
          classification {:escort/classification :whole-ship
                          :escort/confidence :high}]
      (is (= :ship (:part/role-hint (escort/apply-classification part classification))))
      (is (= :geometry (:part/role-source (escort/apply-classification part classification))))))
  (testing "unresolved classifications do not become authoritative"
    (let [part {:part/id "Bundle/Escort/Loose Prow" :part/role-hint :unknown}
          classification {:escort/classification :unresolved
                          :escort/confidence :low}]
      (is (= :unknown (:part/role-hint (escort/apply-classification part classification))))
      (is (nil? (:part/role-source (escort/apply-classification part classification)))))))
