(ns shipyard.loadout.identity
  "Shared saved-model identity validation, independent of paint or persistence."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def name? (m/validator schemas/display-name))
(def part-id? (m/validator schemas/part-id))
(def slot-path? (m/validator schemas/slot-path))
