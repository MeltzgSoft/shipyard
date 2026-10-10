(ns shipyard.mount.preview-wire-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [shipyard.mount.preview-wire :as wire]))

(defn payload-bytes [values] #?(:clj (byte-array (map unchecked-byte values)) :cljs (.-buffer (js/Uint8Array. (clj->js values)))))

(deftest decode-test
  (testing "one shared fixture decodes identically on both runtimes"
    (let [projection (wire/decode (payload-bytes [80 77 89 83 1 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0
                                                  0 0 128 63 0 0 0 64 0 0 64 64 0 0 128 64 0 0 160 64 0 0 192 64]))]
      (is (= [1.0 2.0 3.0 4.0 5.0 6.0] (vec (:cuts projection))))
      (is (empty? (:mirror-cuts projection)))
      (is (empty? (:border projection)))))
  (testing "bad versions, counts and truncated payloads are rejected"
    (doseq [values [[80 77 89 83 2 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0]
                    [80 77 89 83 1 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0]
                    [80 77 89 83]]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (wire/decode (payload-bytes values)))))))

#?(:clj
   (deftest encode-test
     (testing "backend encoding agrees with the cross-runtime fixture"
       (let [decoded (wire/decode (wire/encode {:cuts [[[1.0 2.0 3.0] [4.0 5.0 6.0]]]}))]
         (is (= [1.0 2.0 3.0 4.0 5.0 6.0] (vec (:cuts decoded))))))))
