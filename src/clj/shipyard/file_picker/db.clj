(ns shipyard.file-picker.db
  "One desktop dialog at a time, owned by the application lifecycle."
  (:require [integrant.core :as ig])
  (:import [java.awt AWTError]
           [java.util.concurrent.locks ReentrantLock]))

(defmethod ig/init-key :shipyard.file-picker/db [_ _]
  {:lock (ReentrantLock.)})

(defn- unavailable [cause]
  (ex-info "The desktop file selector is unavailable. Check that Java has desktop support and access to your display."
           {} cause))

(defn choose!
  [{:keys [^ReentrantLock lock]} kind]
  (when-not (.tryLock lock)
    (throw (ex-info "A file selector is already open. Finish or cancel it before opening another." {})))
  (try
    ;; Load desktop classes only when Browse is requested.
    ((requiring-resolve 'shipyard.file-picker.swing/choose!) kind)
    (catch clojure.lang.Compiler$CompilerException e
      ;; Clojure wraps missing imports in a misleading macroexpansion error.
      (if (some #(or (instance? ClassNotFoundException %)
                     (instance? LinkageError %))
                (take-while some? (iterate ex-cause e)))
        (throw (unavailable e))
        (throw e)))
    (catch AWTError e
      (throw (unavailable e)))
    (catch LinkageError e
      (throw (unavailable e)))
    (finally (.unlock lock))))
