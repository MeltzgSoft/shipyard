(ns shipyard.export.geometry
  "Stand socket widening in original STL coordinates, shared by preview and export."
  (:require [shipyard.math :as math]
            [babashka.fs :as fs]
            [shipyard.mesh.stl :as stl]
            [shipyard.pitting.geometry :as geometry])
  (:import [java.nio.file Files]))

(defn stand-cuts [mounts diameter]
  (mapv (fn [mount]
          (let [depth (:mount/stand-depth mount)]
            (when-not (and (math/finite-number? depth) (<= 0.1 depth 100)
                           (every? math/finite-number? (concat (:mount/pos mount) (:mount/axis mount) (:mount/roll mount)))
                           (= 3 (count (:mount/pos mount)))
                           (= 3 (count (:mount/axis mount)))
                           (= 3 (count (:mount/roll mount)))
                           (math/normalize (:mount/axis mount))
                           (< (abs (- 1.0 (math/length (:mount/axis mount)))) 1.0e-6)
                           (< (abs (- 1.0 (math/length (:mount/roll mount)))) 1.0e-6)
                           (< (abs (math/dot (:mount/axis mount) (:mount/roll mount))) 1.0e-6))
              (throw (ex-info "Reauthor the stand mount with a valid frame and socket depth." {})))
            (assoc mount :mount/cut {:kind :cylinder :diameter diameter :depth depth})))
        (filter #(= :stand (:mount/kind %)) mounts)))

(defn bytes! [database diameter entry source]
  (let [cuts (when diameter (stand-cuts (get-in database [:parts (:part-id entry) :part/mounts]) diameter))]
    (if (seq cuts)
      (geometry/binary-stl (geometry/subtract (stl/parse-file! (fs/file source)) cuts))
      (Files/readAllBytes source))))
