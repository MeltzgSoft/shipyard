(ns shipyard.library.index
  "Library root and the mtime+size scan cache.

  Scaffold: resolves and validates the root only. The index itself is issue #13."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :shipyard.library/index [_ {:keys [root]}]
  (let [dir (io/file root)]
    (when-not (.isDirectory dir)
      ;; Not fatal: the app must still start so the user can point it somewhere
      ;; real from the UI. A hard failure here makes a fresh install unusable.
      (log/warn "library root does not exist:" root))
    {:root      root
     :available (.isDirectory dir)}))
