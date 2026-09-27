(ns shipyard.scheme.color
  "sRGB hex and HSV values for the spectrum control."
  (:require [clojure.string :as str]))

(defn valid-hex? [value] (boolean (and (string? value) (re-matches #"#[0-9a-fA-F]{6}" value))))

(defn hex->hsv [hex]
  (let [[r g b] (mapv #(/ #?(:clj (Integer/parseInt (subs hex % (+ % 2)) 16)
                             :cljs (js/parseInt (subs hex % (+ % 2)) 16)) 255.0) [1 3 5])
        high (max r g b) low (min r g b) delta (- high low)]
    [(if (zero? delta) 0
         (mod (* 60 (cond (= high r) (/ (- g b) delta)
                          (= high g) (+ 2 (/ (- b r) delta))
                          :else (+ 4 (/ (- r g) delta)))) 360))
     (if (zero? high) 0 (/ delta high)) high]))

(defn hsv->hex [[h s v]]
  (let [h (/ (mod h 360) 60) c (* v s) x (* c (- 1 (abs (- (mod h 2) 1)))) m (- v c)
        rgb (case (int h) 0 [c x 0] 1 [x c 0] 2 [0 c x] 3 [0 x c] 4 [x 0 c] [c 0 x])]
    (str "#" (apply str (map (fn [n]
                               (let [value (int (Math/round (* 255.0 (+ m n))))
                                     hex #?(:clj (Integer/toHexString value) :cljs (.toString value 16))]
                                 (if (= 1 (count hex)) (str "0" hex) hex))) rgb)))))

(defn picker-value
  "Retain gesture coordinates only when they describe the authoritative color.
  RGB alone cannot recover hue for gray, or hue/saturation for black."
  [hex candidate]
  (if (and (vector? candidate) (= 3 (count candidate)) (every? number? candidate)
           (<= 0 (nth candidate 0) 359) (<= 0 (nth candidate 1) 1) (<= 0 (nth candidate 2) 1)
           (= (str/lower-case hex) (hsv->hex candidate)))
    candidate
    (hex->hsv hex)))
