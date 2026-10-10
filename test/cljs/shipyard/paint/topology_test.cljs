(ns shipyard.paint.topology-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [shipyard.paint.topology :as topology]
            [shipyard.paint.faces :as faces]))

(defn fixture [n]
  (let [buffer (js/ArrayBuffer. (+ 8 (* n 112))) header (js/Uint32Array. buffer 0 2)
        positions (js/Float32Array. buffer 8 (* n 9))
        words (js/Uint32Array. buffer (+ 8 (* n 72)) (* n 9))
        sorted (js/Uint32Array. buffer (+ 8 (* n 108)) n)
        points [[0 0 0] [1 0 0] [0 1 0]] key (faces/face-key points)]
    (aset header 0 1) (aset header 1 n)
    (dotimes [triangle n]
      (.set positions (clj->js (mapcat identity points)) (* triangle 9))
      (dotimes [word 9] (aset words (+ (* triangle 9) word) (js/parseInt (subs key (* word 8) (* (inc word) 8)) 16)))
      (aset sorted triangle triangle))
    buffer))

(deftest zero-copy-identity-and-lookup
  (testing "backend word identity preserves duplicates with logarithmic lookup"
    (let [buffer (fixture 3) prepared (topology/decode buffer)
          key (faces/face-key [[0 0 0] [1 0 0] [0 1 0]])]
      (is (identical? buffer (.-buffer (.-positions prepared))))
      (is (= key (topology/face-key prepared 1)))
      (is (nil? (.-faceKeys prepared)) "On-demand identities retain no growing string cache")
      (is (= [0 1 2] (topology/triangles prepared key)))
      (is (= [] (topology/triangles prepared (apply str (repeat 72 "f")))))))
  (testing "corrupt resources fail before creating geometry"
    (is (thrown? js/Error (topology/decode (js/ArrayBuffer. 8))))))

(deftest source-cache-bounds-pending-and-completed-resources
  (async done
         (reset! topology/source-cache {})
         (let [callbacks (atom {})]
           (with-redefs [topology/cache-limits {:entries 2 :bytes 200}
                         topology/load! (fn [_ mesh-key _ ready! failed!] (swap! callbacks assoc mesh-key [ready! failed!]))]
             (let [a (topology/fetch! "part" "a") b (topology/fetch! "part" "b") c (topology/fetch! "part" "c")]
               (is (= #{"b" "c"} (set (keys @topology/source-cache))) "Pending resources obey admission count")
               (doseq [key ["a" "b" "c"]] ((first (get @callbacks key)) (topology/decode (fixture 1))))
               (-> (js/Promise.all #js [a b c])
                   (.then (fn [values]
                            (is (= 3 (.-length values)) "Eviction leaves existing consumers running")
                            (is (= #{"c"} (set (keys @topology/source-cache))) "Completion cannot recreate evicted entries or exceed bytes")
                            (is (= 120 (get-in @topology/source-cache ["c" :size])) "The binary header counts toward retained bytes")
                            (with-redefs [topology/cache-limits {:entries 2 :bytes 200}
                                          topology/load! (fn [_ mesh-key _ ready! failed!] (swap! callbacks assoc mesh-key [ready! failed!]))]
                              (let [oversized (topology/fetch! "part" "oversized")]
                                ((first (get @callbacks "oversized")) (topology/decode (fixture 2)))
                                oversized))))
                   (.then (fn [source]
                            (is (= 2 (.-count source)) "Oversized topology remains usable by its consumer")
                            (is (= #{"c"} (set (keys @topology/source-cache))) "Oversized completion is never retained")
                            (reset! topology/source-cache {}) (done)))
                   (.catch (fn [error] (is false (str error)) (reset! topology/source-cache {}) (done)))))))))

(deftest stale-source-promise-cannot-remove-its-replacement
  (async done
         (reset! topology/source-cache {})
         (let [callbacks (atom [])]
           (with-redefs [topology/cache-limits {:entries 1 :bytes 200}
                         topology/load! (fn [_ _ _ ready! failed!] (swap! callbacks conj [ready! failed!]))]
             (let [old (topology/fetch! "part" "source") _ (topology/fetch! "part" "other")
                   current (topology/fetch! "part" "source")]
               (is (not (identical? old current)))
               ((second (first @callbacks)) (js/Error. "Old request failed"))
               ((first (nth @callbacks 2)) (topology/decode (fixture 1)))
               (-> current
                   (.then (fn [_] (js/Promise.resolve nil)))
                   (.then (fn [_]
                            (is (identical? current (get-in @topology/source-cache ["source" :promise])) "Old rejection cannot evict a newer claim")
                            (is (= 120 (get-in @topology/source-cache ["source" :size])))
                            (reset! topology/source-cache {}) (done)))
                   (.catch (fn [error] (is false (str error)) (reset! topology/source-cache {}) (done)))))))))
