(ns shipyard.viewport-loading.rules)

(defn brush-workflow?
  "Brush workflows retain their own pending feedback without a viewport overlay."
  [workspace tab customize? trimming?]
  (case workspace
    :browse (or (= tab "regions") (and (= tab "mounts") trimming?))
    :ships customize?
    false))
