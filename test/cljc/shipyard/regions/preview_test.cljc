(ns shipyard.regions.preview-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [shipyard.regions.model :as model]))

(deftest stable-layer-colors
  (let [names ["Primary" "Secondary" "Trim" "Running Lights" "引擎" "🚀"]
        palette #(model/preview-materials {:layers %})
        all (palette names)]
    (doseq [name names]
      (is (= (get all name) (get (palette [name]) name)))
      (is (every? #(<= 0 % 1) (:base (get all name)))))
    (is (= all (palette (reverse names))))
    (is (not= (get all "Trim") (get all "Running Lights")))
    ;; Fixed RGB samples also prove JVM legends and browser buffers agree,
    ;; including a name containing a UTF-16 surrogate pair.
    (doseq [[name expected] [["A" [0.85 0.91 0.19]] ["🚀" [0.91 0.514 0.19]]]]
      (is (every? #(< (abs %) 1.0e-9) (map - expected (:base (get (palette [name]) name))))))))
