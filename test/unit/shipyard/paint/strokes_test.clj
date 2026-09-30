(ns shipyard.paint.strokes-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.strokes :as strokes]))

(def triangle [[0 0 0] [1 0 0] [0 1 0]])
(def key-a (faces/face-key triangle))
(def key-b (faces/face-key (mapv #(update % 2 inc) triangle)))
(def hash-a (apply str (repeat 64 "a")))

(deftest stable-identity-and-strict-inputs
  (is (= 72 (count key-a)))
  (is (not= key-a (faces/face-key (reverse triangle))) "Back-to-back faces stay independent")
  (is (= key-a (faces/face-key [[0 -0.0 0] [1 0 0] [0 1 0]])))
  (is (= [key-a] (strokes/parse-faces (pr-str [key-a]))))
  (is (= 1025 (count (strokes/parse-faces (pr-str (vec (repeat 1025 key-a)))))))
  (doseq [value ["[]" "nil" "[1]" "[] []" "#=(System/exit 0)"]]
    (is (nil? (strokes/parse-faces value))))
  (is (= #{key-a} (strokes/mesh-faces {:positions (float-array [0 0 0 1 0 0 0 1 0]) :indices (int-array [2 0 1])}))))

(deftest masks-erase-and-history
  (let [a (:layer (faces/stroke nil "hull" hash-a [key-a] {:base [1 0 0] :metalness 0 :roughness 1} false))
        b (:layer (faces/stroke a "hull" hash-a [key-b] {:base [0 1 0] :metalness 0 :roughness 1} false))
        history (-> nil (strokes/commit-history {} {[] a}) (strokes/commit-history {[] a} {[] b}))
        undo (strokes/history-change {[] b} history "undo" [])
        redo (strokes/history-change (:details undo) (:history undo) "redo" [])
        erase (:layer (faces/stroke b "hull" hash-a [key-a] {:base [1 0 0] :metalness 0 :roughness 1} true))]
    (is (= {[] a} (:details undo)))
    (is (= {[] b} (:details redo)))
    (is (= history (strokes/commit-history history {[] b} {[] b}))
        "Retrying an already committed stroke does not add an undo step")
    (is (= {key-b {:base [0 1 0] :metalness 0 :roughness 1}} (:faces erase)))
    (is (= :changed-source (:error (faces/stroke b "other" hash-a [key-a] {:base [1 0 0] :metalness 0 :roughness 1} false))))
    (is (= :changed-source (:error (faces/stroke b "hull" (apply str (repeat 64 "b")) [key-a] {:base [1 0 0] :metalness 0 :roughness 1} false))))
    (is (= :no-undo (:error (strokes/history-change {[] a} history "undo" []))))
    (is (= {} (:details (strokes/history-change {[] b} history "clear" []))))
    (is (= 20 (count (:undo (reduce #(strokes/commit-history %1 {:before %2} {:after %2}) nil (range 25))))))))

(deftest unbounded-layer-and-incremental-parts
  (let [keys (mapv #(faces/face-key [[% 0 0] [% 1 0] [% 0 1]]) (range 100001))
        layer (:layer (faces/stroke nil "hull" hash-a keys {:base [1 0 0] :metalness 0 :roughness 1} false))
        pending {:layers {[] layer}}
        next (strokes/apply-part pending [{:path [] :part-id "hull" :mesh-key hash-a :keys [(first keys)]}]
                                 {:base [0 1 0] :metalness 0 :roughness 1} false)]
    (is (= 100001 (count (:faces layer))))
    (is (= 100001 (count (get-in next [:layers [] :faces]))))
    (is (= {:base [0 1 0] :metalness 0 :roughness 1} (get-in next [:layers [] :faces (first keys)])))
    (is (= {:base [1 0 0] :metalness 0 :roughness 1} (get-in next [:layers [] :faces (second keys)])))))

(deftest selected-face-keys
  (let [ordered [key-b key-a key-b]]
    (is (= [key-b key-b] (strokes/selected-face-keys ordered {:triangle-count 3 :indices [2 0]})))
    (is (nil? (strokes/selected-face-keys ordered {:triangle-count 2 :indices [0]})))
    (is (nil? (strokes/selected-face-keys ordered {:triangle-count 3 :indices [3]})))
    (is (nil? (strokes/selected-face-keys ordered {:triangle-count 3 :indices []}))))
  (is (= [key-b key-a]
         (strokes/mesh-face-keys {:positions (float-array [0 0 1 1 0 1 0 1 1 0 0 0 1 0 0 0 1 0])
                                  :indices (int-array [0 1 2 3 4 5])}))))
