(ns shipyard.assembly.scene
  "Pure scene identity and stale-completion decisions; no compatibility or attachment math.")

(def empty-state {:mode :browse :sequence -1 :generation 0 :slots {}})

(defn leave
  "Invalidate all pending assembly fetches when browsing or authoring takes over."
  [state]
  (-> state (assoc :mode :browse :slots {}) (update :generation inc)))

(defn accept-event
  "Apply ordered commands only from newer server responses. Stable identical sets retain tokens."
  [state {:keys [sequence revision commands]}]
  (if (or (not (number? sequence)) (<= sequence (:sequence state))
          (and (not= :assembly (:mode state)) (not-any? #(= :reset (:op %)) commands)))
    state
    (reduce
     (fn [state [index {:keys [op slot] :as command}]]
       (case op
         :reset (-> state (assoc :slots {} :mode :assembly) (update :generation inc))
         :remove (update state :slots dissoc slot)
         :set (let [payload (dissoc command :op)]
                (if (= payload (get-in state [:slots slot :payload]))
                  state
                  (assoc-in state [:slots slot]
                            {:payload payload :token [(:generation state) sequence index]})))
         state))
     (assoc state :sequence sequence :revision revision)
     (map-indexed vector commands))))

(defn current?
  "True only while the slot still owns this fetch token in assembly mode."
  [state slot token]
  (and (= :assembly (:mode state)) (some? token)
       (= token (get-in state [:slots slot :token]))))
