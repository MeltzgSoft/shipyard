(ns shipyard.workspace.db-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.workspace.db :as w]
            [shipyard.workspace.transforms :as transforms]))

(deftest owner-test
  (is (= :browse (w/owner "/orient/save")))
  (is (= :ships (w/owner "/assembly/save")))
  (is (= :ships (w/owner "/ships/edit")))
  (is (= :browse (w/owner "/part/navy/hull")))
  (is (nil? (w/owner "/workspace/ships"))))

(deftest selected-filters-test
  (is (= [:form [:select {:name "bundle"}
                 [[:option {:value "a" :selected false} "A"]
                  [:option {:value "b" :selected true} "B"]]]]
         (transforms/selected-filters
          [:form [:select {:name "bundle"} [[:option {:value "a"} "A"] [:option {:value "b"} "B"]]]]
          {"bundle" "b"}))))

(deftest request-ordering-test
  (is (not (transforms/current-request? 2 3 :ships :ships)))
  (is (transforms/current-request? 3 3 :ships :ships))
  (is (not (transforms/current-request? 3 2 :ships :ships)))
  (is (not (transforms/current-request? 3 3 :ships :assembly))))

(deftest editor-color-defaults-and-independent-toggle-choices
  (let [assembly {:view :editor :inspector-tab "assembly" :colors false}
        off (transforms/toggle-colors assembly :ships)
        schemes (assoc off :inspector-tab "schemes")
        schemes-on (transforms/toggle-colors schemes :ships)
        paint (assoc schemes-on :inspector-tab "paint")]
    (is (transforms/mount-colors? assembly :ships))
    (is (not (transforms/mount-colors? off :ships)))
    (is (not (transforms/mount-colors? schemes :ships)))
    (is (transforms/mount-colors? schemes-on :ships))
    (is (not (transforms/mount-colors? paint :ships)))
    (is (not (transforms/mount-colors? (assoc paint :inspector-tab "assembly") :ships)))
    (is (transforms/mount-colors? (assoc paint :inspector-tab "schemes") :ships))
    (is (not (transforms/mount-colors? (assoc off :view :table) :ships)))
    (is (transforms/mount-colors? {:view :editor} :ships))
    (is (not (transforms/mount-colors? {:colors false} :ships)))
    (is (transforms/mount-colors? {:colors true} :browse))
    (is (= {:colors true :tab-colors {"part" false}} (transforms/toggle-colors {:colors true} :browse)))))

(deftest part-browser-tools-retain-independent-color-choices
  (let [part {:view :part :selection "one" :inspector-tab "part"}
        part-off (transforms/toggle-colors part :browse)
        mounts (assoc part-off :inspector-tab "mounts")
        mounts-off (transforms/toggle-colors mounts :browse)
        regions (assoc mounts-off :inspector-tab "regions")
        regions-on (transforms/toggle-colors regions :browse)
        orientation (assoc regions-on :view :grid)
        orientation-off (transforms/toggle-colors orientation :browse)]
    (is (transforms/mount-colors? part :browse))
    (is (not (transforms/mount-colors? part-off :browse)))
    (is (transforms/mount-colors? mounts :browse))
    (is (not (transforms/mount-colors? mounts-off :browse)))
    (is (not (transforms/mount-colors? regions :browse)))
    (is (transforms/mount-colors? regions-on :browse))
    (is (transforms/mount-colors? orientation :browse))
    (is (not (transforms/mount-colors? orientation-off :browse)))
    (is (transforms/mount-colors? (assoc orientation-off :view :variant) :browse))
    (is (transforms/mount-colors? (assoc orientation-off :view :part) :browse))
    (is (not (transforms/mount-colors? (assoc orientation-off :view :part :inspector-tab "mounts") :browse)))))

(deftest selecting-parts-preserves-tool-preferences-and-resets-new-part-inspectors
  (let [state {:view :part :selection "one" :inspector-tab "regions" :tab-colors {"regions" true}}]
    (is (= state (transforms/select-part state "one")))
    (is (= "part" (:inspector-tab (transforms/select-part state "one" true))))
    (is (= {:view :part :selection "two" :inspector-tab "part" :tab-colors {"regions" true}}
           (transforms/select-part state "two")))))

(deftest drawer-states-test
  (let [parent [[:weapon 0]] child [[:weapon 0] [:turret 0]]
        incomplete [{:id parent :assigned "weapon"} {:id child :assigned nil}]
        complete (assoc-in incomplete [1 :assigned] "turret")]
    (is (= {parent {:complete false :open true} child {:complete false :open true}}
           (transforms/drawer-states {} incomplete)))
    (is (false? (get-in (transforms/drawer-states {parent {:complete false :open false}} incomplete) [parent :open])))
    (is (= {parent {:complete true :open false} child {:complete true :open false}}
           (transforms/drawer-states {} complete)))
    (is (true? (get-in (transforms/drawer-states {parent {:complete true :open false}} incomplete) [parent :open])))
    (is (= {} (transforms/drawer-states {parent {:complete true :open false}} [])))))
