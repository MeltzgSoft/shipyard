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
  (doseq [value ["[]" "nil" "[1]" "[] []" "#=(System/exit 0)" (pr-str (vec (repeat 1025 key-a)))]]
    (is (nil? (strokes/parse-faces value))))
  (is (= #{key-a} (strokes/mesh-faces {:positions (float-array [0 0 0 1 0 0 0 1 0]) :indices (int-array [2 0 1])}))))

(deftest masks-erase-history-and-schema
  (let [a (strokes/change-layer nil nil "paint" "hull" hash-a [key-a] [1 0 0])
        b (strokes/change-layer (:layer a) (:history a) "paint" "hull" hash-a [key-b] [0 1 0])
        undo (strokes/change-layer (:layer b) (:history b) "undo" nil nil nil nil)
        redo (strokes/change-layer (:layer undo) (:history undo) "redo" nil nil nil nil)
        erase (strokes/change-layer (:layer b) (:history b) "erase" "hull" hash-a [key-a] [1 0 0])
        record {:scheme/id (random-uuid) :scheme/name "Details" :scheme/roles {} :scheme/details {[] (:layer b)}}]
    (is (= (:layer a) (:layer undo)))
    (is (= (:layer b) (:layer redo)))
    (is (= (:history b) (:history (strokes/change-layer (:layer b) (:history b) "paint" "hull" hash-a [key-b] [0 1 0])))
        "Retrying an already committed stroke does not add an undo step")
    (is (= {key-b [0 1 0]} (:faces (:layer erase))))
    (is (scheme/scheme? record))
    (is (false? (scheme/scheme? (assoc-in record [:scheme/details [] :faces] 1))))
    (is (not (scheme/scheme? (assoc-in record [:scheme/details [] :faces key-a] [##NaN 0 0]))))
    (is (= :changed-source (:error (faces/stroke (:layer b) "other" hash-a [key-a] [1 0 0] false))))
    (is (= :changed-source (:error (faces/stroke (:layer b) "hull" (apply str (repeat 64 "b")) [key-a] [1 0 0] false))))
    (is (= :no-undo (:error (strokes/change-layer (:layer a) (:history b) "undo" nil nil nil nil))))
    (is (nil? (:layer (strokes/change-layer (:layer b) (:history b) "clear" nil nil nil nil))))))
