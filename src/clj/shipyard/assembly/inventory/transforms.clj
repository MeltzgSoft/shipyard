(ns shipyard.assembly.inventory.transforms
  "Pure staging of inventory quantities across filtered browser pages."
  (:require [clojure.string :as str]))

(defn current? [picker draft token]
  (boolean (and picker (= (:token picker) token)
                (= (:revision picker) (:revision draft)) (= (:hull picker) (:hull draft)))))

(defn quantities [before params]
  (reduce-kv
   (fn [result field value]
     (if (or (:error result) (not (str/starts-with? field "quantity."))) result
         (let [id (subs field 9)
               quantity (when (and (string? value) (re-matches #"[0-9]{1,7}" value)) (parse-long value))]
           (if (and quantity (<= 0 quantity 1000000))
             (update result :quantities #(if (zero? quantity) (dissoc % id) (assoc % id quantity)))
             (assoc result :error :invalid-quantity)))))
   {:quantities before} params))
