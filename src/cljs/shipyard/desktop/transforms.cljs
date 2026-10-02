(ns shipyard.desktop.transforms
  "Pure desktop protocol and navigation decisions; no Electron dependency."
  (:require [clojure.string :as str]))

(def ready-prefix "SHIPYARD_DESKTOP_READY")
(def stop-command "SHIPYARD_DESKTOP_STOP\n")
(def max-line-bytes 4096)

(defn readiness [token line]
  (when (str/starts-with? line (str ready-prefix " "))
    (let [[prefix received port & extra] (str/split line #" ")
          number (js/Number port)]
      (when (= token received)
        (if (and (= prefix ready-prefix) (nil? extra)
                 (re-matches #"[0-9]+" (or port ""))
                 (js/Number.isInteger number) (<= 8080 number 65535))
          {:port number :url (str "http://127.0.0.1:" number)}
          {:error "Invalid owned backend readiness"})))))

(defn- url-value [value]
  (try (js/URL. value) (catch :default _ nil)))

(defn same-origin? [origin target]
  (let [a (url-value origin) b (url-value target)]
    (boolean (and a b (= (.-origin a) (.-origin b)) (= (.-protocol a) (.-protocol b))
                  (empty? (.-username b)) (empty? (.-password b))))))

(defn safe-external? [target]
  (let [url (url-value target)]
    (boolean (and url (#{"http:" "https:"} (.-protocol url))
                  (not-empty (.-hostname url))
                  (empty? (.-username url)) (empty? (.-password url))))))
