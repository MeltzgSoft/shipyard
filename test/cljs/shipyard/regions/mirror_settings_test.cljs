(ns shipyard.regions.mirror-settings-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [shipyard.regions.mirror :as mirror]))

(deftest controls-follow-authoritative-source-and-orientation
  (let [source ["part" "mesh"] pose [0 0 0 1]
        current {:part-id "part" :mesh-key "mesh" :orientation pose}
        chosen {:key (conj source pose) :enabled true :axis :z :offset "10"}]
    (testing "same-source reloads and an unrelated retained selection preserve controls"
      (is (= chosen (mirror/settings-for-source chosen source nil)))
      (is (= chosen (mirror/settings-for-source chosen source {:part-id "other" :mesh-key "other" :orientation [0 1 0 0]})))
      (is (= chosen (mirror/settings-for-source chosen source current))))
    (testing "first resolved pose preserves controls chosen while the mesh was pending"
      (is (= chosen (mirror/settings-for-source (assoc chosen :key (conj source nil)) source current))))
    (testing "real orientation and source changes reset transient mirror controls"
      (is (= {:key ["part" "mesh" [0 1 0 0]] :enabled false :axis :x :offset ""}
             (mirror/settings-for-source chosen source (assoc current :orientation [0 1 0 0]))))
      (is (= {:key ["part" "new-mesh" nil] :enabled false :axis :x :offset ""}
             (mirror/settings-for-source chosen ["part" "new-mesh"] current)))
      (is (= {:key ["new-part" "mesh" nil] :enabled false :axis :x :offset ""}
             (mirror/settings-for-source chosen ["new-part" "mesh"] nil))))))
