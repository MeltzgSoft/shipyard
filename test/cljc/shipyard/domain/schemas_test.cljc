(ns shipyard.domain.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [shipyard.domain.schemas :as s]))

(def id #uuid "8621a7ab-c9e7-43a7-a2b9-8e0700589fb1")
(def other-id #uuid "8621a7ab-c9e7-43a7-a2b9-8e0700589fb2")
(def material {:base [0 0.5 1] :metalness 0 :roughness 1})
(def member {:path [] :part-id "hull"})
(def group {:group/id id :group/name "Group" :group/order 0 :group/members [member]})
(def layer-id (str "layer:" id))
(def mesh-key (apply str (repeat 64 "a")))
(def face-key (apply str (repeat 72 "a")))
(def detail {:part-id "hull" :mesh-key mesh-key :faces {face-key material}})

(deftest identities-and-paths-test
  (testing "bounded identities and nonblank names"
    (doseq [[schema valid invalid]
            [[s/display-name ["a" " a " (apply str (repeat 200 "x"))] [nil "" " " (apply str (repeat 201 "x"))]]
             [s/part-id [" " (apply str (repeat 2048 "x"))] [nil "" (apply str (repeat 2049 "x"))]]
             [s/layer-name ["a" "a b"] ["" " a" "a "]]
             [s/face-key [face-key] [(str face-key "\n") "a" 1]]
             [s/mesh-key [mesh-key] [(str mesh-key "\n") "a" 1]]
             [s/detail-id [layer-id] [(str layer-id "\n") (str id) "Primary"]]]]
      (doseq [value valid] (is (m/validate schema value) (pr-str value)))
      (doseq [value invalid] (is (not (m/validate schema value)) (pr-str value)))))
  (testing "root allowed only for paint instances, 16 slots and 0..255 ordinals"
    (is (m/validate s/instance-path []))
    (is (m/validate s/instance-path '()))
    (is (not (m/validate s/slot-path [])))
    (doseq [path [[[:weapon 0]] [[:weapon 255]]]]
      (is (m/validate s/slot-path path)))
    (is (m/validate s/slot-path (vec (repeat 16 [:weapon 1]))))
    (doseq [path [nil '([:weapon 1]) ['(:weapon 1)] [[:weapon -1]] [[:weapon 256]] [["weapon" 1]]
                  [[:weapon 1.5]] [[:weapon 0 :extra]] (vec (repeat 17 [:weapon 1]))]]
      (is (not (m/validate s/slot-path path))))))

(deftest materials-and-groups-test
  (testing "finite channels, optional defaults and closed material maps"
    (is (m/validate s/material material))
    (is (m/validate s/material (assoc material :glow 0 :paint "")))
    (doseq [value [nil "0" -0.01 1.01 ##NaN ##Inf ##-Inf]
            field [:metalness :roughness :glow]]
      (is (not (m/validate s/material (assoc material field value)))))
    (doseq [value [(dissoc material :base) (assoc material :base '(0 0 0))
                   (assoc material :base [0 0]) (assoc material :base [0 0 ##NaN])
                   (assoc material :paint nil) (assoc material :paint (apply str (repeat 201 "x")))
                   (assoc material :extra true)]]
      (is (not (m/validate s/material value))))
    (is (not (m/validate s/detail-material (assoc material :paint "Blue")))))
  (testing "member identity, uniqueness and explicit order"
    (is (m/validate s/groups [group]))
    (is (m/validate s/groups [group (assoc group :group/id other-id :group/order 3)]))
    (doseq [value [[group group] [group (assoc group :group/id other-id)]
                   [(assoc group :group/members [member member])]
                   [(assoc group :group/members [(assoc member :extra true)])]
                   [(assoc group :group/order -1)] [(dissoc group :group/name)]]]
      (is (not (m/validate s/groups value))))))

(deftest records-and-limits-test
  (testing "closed durable records and open editor profiles"
    (let [loadout {:loadout/id id :loadout/name "Class" :loadout/hull "hull" :loadout/slots {}}
          scheme {:scheme/id id :scheme/name "Fleet" :scheme/layers {"Primary" material}}
          ship {:ship/id id :ship/name "Ship" :ship/class other-id :ship/paint {}}]
      (doseq [[schema record] [[s/loadout loadout] [s/scheme scheme] [s/ship ship]]]
        (is (m/validate schema record))
        (is (not (m/validate schema (assoc record :extra 1))))
        (doseq [key (keys record)] (is (not (m/validate schema (dissoc record key))))))
      (is (not (m/validate s/ship (assoc ship :ship/scheme nil))))
      (is (m/validate s/custom-paint {:editor/state true}))
      (is (not (m/validate s/paint-job {:editor/state true})))
      (is (m/validate (s/record-store :loadouts :loadout/id s/loadout) {:version 1 :loadouts {id loadout}}))
      (is (not (m/validate (s/record-store :loadouts :loadout/id s/loadout) {:version 1 :loadouts {other-id loadout}})))))
  (testing "nested paint fields validate without requiring optional collections"
    (is (m/validate s/paint-job {:paint/groups [group] :paint/details {[] detail}
                                 :paint/instances {[] {:part-id "hull" :material material}}}))
    (doseq [value [{:paint/details {[] (assoc detail :mesh-key "bad")}}
                   {:paint/details {[] (assoc-in detail [:faces face-key :base] [1 2 3])}}
                   {:paint/instances {[] {:part-id "hull" :material {}}}}
                   {:paint/groups nil} {:paint/layers {"not-a-layer" material}}]]
      (is (not (m/validate s/paint-job value)))))
  (testing "4096 class slots; 4097 paint instances/details including root"
    (let [paths (mapv #(vector [(keyword (str "slot-" %)) 0]) (range 4098))]
      (doseq [[schema value limit] [[(nth (last s/loadout) 1) "part" 4096]
                                    [s/instances {:part-id "part" :material material} 4097]
                                    [s/details detail 4097]]]
        (is (m/validate schema (zipmap (take limit paths) (repeat value))))
        (is (not (m/validate schema (zipmap (take (inc limit) paths) (repeat value)))))))))

(deftest regions-and-registry-test
  (testing "region definitions remain open, registry definitions stay closed"
    (let [definition {:name "Trim" :preview-name "Trim" :preview-color [0 0 1]}
          regions {:version 2 :revision 0 :mesh-key mesh-key :layers ["Primary" "Secondary" layer-id]
                   :layer-definitions {layer-id definition} :faces {face-key layer-id}}
          registry {:version 1 :revision 0 :layers {layer-id definition} :deleted #{}}]
      (is (m/validate s/regions regions))
      (is (m/validate s/registry registry))
      (is (m/validate s/regions (assoc-in regions [:layer-definitions layer-id :extra] true)))
      (is (not (m/validate s/registry (assoc-in registry [:layers layer-id :extra] true))))
      (doseq [value [(assoc regions :layers ["Secondary" "Primary" layer-id])
                     (update regions :layers conj layer-id) (assoc regions :layers ["Primary" "Secondary"])
                     (assoc regions :revision -1) (assoc regions :extra true)
                     (assoc-in regions [:layer-definitions layer-id :preview-color] [##NaN 0 0])
                     (assoc-in regions [:faces face-key] (str "layer:" other-id))]]
        (is (not (m/validate s/regions value))))
      (is (not (m/validate s/registry (assoc registry :deleted #{layer-id})))))))

(deftest selection-schemas-test
  (testing "bounded facets and nonempty face selections"
    (is (m/validate s/facet-indices [0 2147483647]))
    (is (m/validate s/facet-indices (vec (repeat 4096 0))))
    (doseq [value [[] [-1] [2147483648] [0.5] (vec (repeat 4097 0))]]
      (is (not (m/validate s/facet-indices value))))
    (is (s/valid-face-keys? [face-key]))
    (is (not (s/valid-face-keys? [])))
    (is (m/validate s/stroke-entries [{:target "" :mesh-key "" :faces [face-key]}]))
    (is (not (m/validate s/stroke-entries [{:target "" :mesh-key "" :faces []}])))))
