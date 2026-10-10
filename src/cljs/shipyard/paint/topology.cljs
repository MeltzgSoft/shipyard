(ns shipyard.paint.topology
  "Zero-copy backend topology; lazy picking identities and logarithmic mask lookup."
  (:require [shipyard.preparation :as preparation]))

(defn decode [buffer]
  (let [header (js/Uint32Array. buffer 0 2) n (aget header 1)]
    (when-not (and (= 1 (aget header 0)) (= (.-byteLength buffer) (+ 8 (* n 112))))
      (throw (js/Error. "Invalid paint topology.")))
    #js {:positions (js/Float32Array. buffer 8 (* n 9))
         :normals (js/Float32Array. buffer (+ 8 (* n 36)) (* n 9))
         :words (js/Uint32Array. buffer (+ 8 (* n 72)) (* n 9))
         :sorted (js/Uint32Array. buffer (+ 8 (* n 108)) n)
         :count n}))

(defn face-key [^js topology triangle]
  (apply str (for [word (range 9)] (.padStart (.toString (aget (.-words topology) (+ (* triangle 9) word)) 16) 8 "0"))))

(defn triangles
  "Binary search immutable canonical identity words; preserve duplicate triangles."
  [^js topology key]
  (let [words (mapv #(js/parseInt (subs key (* % 8) (* (inc %) 8)) 16) (range 9))
        sorted (.-sorted topology)
        compare! (fn [index]
                   (let [triangle (aget sorted index)]
                     (loop [word 0]
                       (if (= word 9) 0
                           (let [a (aget (.-words topology) (+ (* triangle 9) word)) b (nth words word)]
                             (cond (< a b) -1 (> a b) 1 :else (recur (inc word))))))))]
    (loop [lo 0 hi (.-count topology)]
      (if (< lo hi)
        (let [mid (js/Math.floor (/ (+ lo hi) 2))]
          (if (neg? (compare! mid)) (recur (inc mid) hi) (recur lo mid)))
        (loop [index lo result []]
          (if (and (< index (.-count topology)) (zero? (compare! index)))
            (recur (inc index) (conj result (aget sorted index))) result))))))

(defn load! [part-id mesh-key current? ready! failed!]
  (preparation/load! (str "/paint/preparation/topology?part-id=" (js/encodeURIComponent part-id) "&mesh-key=" mesh-key "&retry=1")
                     {:current? current? :decode! #(.arrayBuffer %) :ready! #(ready! (decode %)) :failed! failed!}))

(defonce source-cache (atom {}))
(defonce source-sequence (atom 0))
(def cache-limits {:entries 16 :bytes 134217728})

(defn- bounded-cache [cache {:keys [entries bytes]}]
  (loop [cache cache]
    (if (or (> (count cache) entries) (> (reduce + 0 (map :size (vals cache))) bytes))
      (recur (dissoc cache (key (apply min-key (comp :order val) cache))))
      cache)))

(defn- current-entry? [cache mesh-key promise]
  (identical? promise (get-in cache [mesh-key :promise])))

(defn fetch!
  "Reuse immutable topology buffers across instances; bound browser resident source data."
  [part-id mesh-key]
  (or (get-in @source-cache [mesh-key :promise])
      (let [limits cache-limits
            promise (js/Promise. (fn [resolve reject]
                                   (load! part-id mesh-key (constantly true) resolve reject)))]
        ;; Eviction forgets reuse, leaving consumers' promises and views alive.
        (swap! source-cache #(bounded-cache (assoc % mesh-key {:promise promise :size 0 :order (swap! source-sequence inc)}) limits))
        (-> promise
            (.then (fn [^js topology]
                     (let [size (.. topology -positions -buffer -byteLength)]
                       (swap! source-cache
                              #(if-not (current-entry? % mesh-key promise) %
                                       (if (> size (:bytes limits)) (dissoc % mesh-key)
                                           (bounded-cache (assoc-in % [mesh-key :size] size) limits)))))
                     topology))
            (.catch (fn [_error]
                      (swap! source-cache #(if (current-entry? % mesh-key promise) (dissoc % mesh-key) %)))))
        promise)))
