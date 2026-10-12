(ns shipyard.viewport-loading.rules)

(defn stroke-request?
  "Only painting/erasing strokes use their own save feedback. History is a job."
  [path history operation]
  (or (= path "/parts/regions/stroke")
      (and (= path "/ships/paint/stroke")
           (not (or (= history "undo") (= history "redo")
                    (= operation "undo") (= operation "redo"))))))
