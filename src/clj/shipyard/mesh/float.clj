(ns shipyard.mesh.float
  "Shared float representation helpers for mesh algorithms.")

(defn canonical-bits
  "Float bits with -0.0 folded onto 0.0 for exact position comparison."
  ^long [value]
  (Float/floatToRawIntBits
   (float (if (zero? (double value)) 0.0 value))))
