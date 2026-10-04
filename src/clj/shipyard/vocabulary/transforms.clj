(ns shipyard.vocabulary.transforms
  "Shared classification names and role identifiers."
  (:require [clojure.string :as str]))

(def roles [:hull :hull-section :prow :bridge :antenna :engine :weapon :turret
            :stern :fin :section :detail :ordinance :terrain :unknown])

(def universal-class "Universal")
(def builtins {:bundle #{} :class #{universal-class} :role (set (map name roles))})

(defn role [value]
  (let [s (some-> value (str) (str/trim) (str/lower-case) (str/replace #"\s+" "-"))]
    (when (and s (<= (count s) 80) (re-matches #"[a-z][a-z0-9_-]*" s)
               (not= s "turret-or-antenna"))
      (keyword s))))

(defn entry [field value]
  (let [value (str/trim (or value ""))
        value (if (= field "role") (some-> (role value) (name)) value)]
    (cond
      (not (#{"bundle" "class" "role"} field)) {:error "Choose faction, class or role."}
      (or (str/blank? value) (> (count value) 120)
          (re-find #"[<>:\"/\\|?*\p{Cntrl}]|[. ]$" value) (#{"." ".."} value))
      {:error "Enter a valid name (up to 120 characters). Roles use letters, numbers, hyphens and underscores."}
      :else {:field (keyword field) :value value})))
