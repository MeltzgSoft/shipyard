(ns shipyard.viewport
  "The browser island. Scaffold: proves three.js bundles and survives :advanced
  compilation. The real scene is issue #16."
  (:require ["three" :as three]))

(goog-define ^boolean TEST-HOOKS false)

(defn ^:export init []
  ;; Touch three.js so :advanced cannot tree-shake it away - this build exists
  ;; to prove externs inference works, which needs a real property access.
  (let [v (three/Vector3. 1 2 3)]
    (js/console.log "shipyard viewport" (.length v)))
  (when TEST-HOOKS
    (set! (.-__shipyard js/window)
          #js {:stats (fn [] #js {:parts #js [] :vertices 0})})))
