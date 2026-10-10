(ns shipyard.mount.cut-render
  "Attach backend-prepared line buffers; no polygon or topology analysis."
  (:require ["three" :as three]))

(defn object!
  ([positions] (object! positions "mount-cut" 0xffdf80))
  ([^js positions object-name color]
   (when (pos? (.-length positions))
     (let [geometry (doto (three/BufferGeometry.)
                      (.setAttribute "position" (three/BufferAttribute. positions 3)))
           object (three/LineSegments. geometry
                                       (three/LineBasicMaterial. #js {:color color :depthTest false :depthWrite false}))]
       (set! (.-name object) object-name)
       (set! (.-renderOrder object) 1000)
       object))))
