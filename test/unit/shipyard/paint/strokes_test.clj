(ns shipyard.paint.strokes-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.strokes :as strokes]
            [shipyard.scheme.transforms :as scheme]))

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

(deftest masks-erase-history-and-schema
  (let [a (:layer (faces/stroke nil "hull" hash-a [key-a] [1 0 0] false))
        b (:layer (faces/stroke a "hull" hash-a [key-b] [0 1 0] false))
        history (-> nil (strokes/commit-history {} {[] a}) (strokes/commit-history {[] a} {[] b}))
        undo (strokes/history-change {[] b} history "undo" [])
        redo (strokes/history-change (:details undo) (:history undo) "redo" [])
        erase (:layer (faces/stroke b "hull" hash-a [key-a] [1 0 0] true))
        record {:scheme/id (random-uuid) :scheme/name "Details" :scheme/roles {} :scheme/details {[] b}}]
    (is (= {[] a} (:details undo)))
    (is (= {[] b} (:details redo)))
    (is (= history (strokes/commit-history history {[] b} {[] b}))
        "Retrying an already committed stroke does not add an undo step")
    (is (= {key-b [0 1 0]} (:faces erase)))
    (is (scheme/scheme? record))
    (is (false? (scheme/scheme? (assoc-in record [:scheme/details [] :faces] 1))))
    (is (not (scheme/scheme? (assoc-in record [:scheme/details [] :faces key-a] [##NaN 0 0]))))
    (is (= :changed-source (:error (faces/stroke b "other" hash-a [key-a] [1 0 0] false))))
    (is (= :changed-source (:error (faces/stroke b "hull" (apply str (repeat 64 "b")) [key-a] [1 0 0] false))))
    (is (= :no-undo (:error (strokes/history-change {[] a} history "undo" []))))
    (is (= {} (:details (strokes/history-change {[] b} history "clear" []))))
    (is (= 20 (count (:undo (reduce #(strokes/commit-history %1 {:before %2} {:after %2}) nil (range 25))))))))

(deftest unbounded-layer-and-incremental-parts
  (let [keys (mapv #(faces/face-key [[% 0 0] [% 1 0] [% 0 1]]) (range 100001))
        layer (:layer (faces/stroke nil "hull" hash-a keys [1 0 0] false))
        pending {:layers {[] layer}}
        next (strokes/apply-part pending [{:path [] :part-id "hull" :mesh-key hash-a :keys [(first keys)]}]
                                 [0 1 0] false)]
    (is (= 100001 (count (:faces layer))))
    (is (scheme/scheme? {:scheme/id (random-uuid) :scheme/name "Large" :scheme/roles {} :scheme/details {[] layer}}))
    (is (= 100001 (count (get-in next [:layers [] :faces]))))
    (is (= [0 1 0] (get-in next [:layers [] :faces (first keys)])))
    (is (= [1 0 0] (get-in next [:layers [] :faces (second keys)])))))
