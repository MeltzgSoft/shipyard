(ns shipyard.mount.cut-render-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [shipyard.mount.cut-render :as render]))

(deftest object-test
  (testing "prepared buffers attach directly and cannot hide subsequent surfaces"
    (let [positions (js/Float32Array. #js [0 0 1 1 0 1])
          ^js object (render/object! positions)]
      (is (identical? positions (.. object -geometry (getAttribute "position") -array)))
      (is (= "mount-cut" (.-name object)))
      (is (false? (.. object -material -depthTest)))
      (is (false? (.. object -material -depthWrite)))
      (is (= 1000 (.-renderOrder object)))
      (.dispose (.-geometry object))
      (.dispose (.-material object))))
  (testing "empty buffers do not allocate GPU objects"
    (is (nil? (render/object! (js/Float32Array. 0))))))
