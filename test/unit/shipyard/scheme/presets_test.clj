(ns shipyard.scheme.presets-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.scheme.presets :as presets]))

(deftest normalize
  (is (= {:color-preset/hex "#d4af37" :color-preset/name "Gold trim"}
         (presets/normalize "#D4AF37" " Gold trim "))))
