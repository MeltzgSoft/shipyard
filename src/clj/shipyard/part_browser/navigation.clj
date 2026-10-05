(ns shipyard.part-browser.navigation
  "Neighbors in the complete, stable filtered table order.")

(defn neighbors [ids current]
  (let [ids (vec ids) index (first (keep-indexed #(when (= current %2) %1) ids))]
    (when index
      {:previous (when (pos? index) (nth ids (dec index)))
       :next (when (< (inc index) (count ids)) (nth ids (inc index)))})))
