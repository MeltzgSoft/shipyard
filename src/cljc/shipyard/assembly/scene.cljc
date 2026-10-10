(ns shipyard.assembly.scene
  "Pure scene identity and stale-completion decisions; no compatibility or attachment math."
  (:require [shipyard.paint.delta :as delta]))

(def empty-state {:mode :browse :sequence -1 :generation 0 :slots {}})

(def mount-colors
  "A high-contrast set reused by the rail, summaries and model."
  [{:css "#6fb1d8" :hex 0x6fb1d8}
   {:css "#d8a25f" :hex 0xd8a25f}
   {:css "#5ce080" :hex 0x5ce080}
   {:css "#d87070" :hex 0xd87070}
   {:css "#b58be8" :hex 0xb58be8}
   {:css "#e18bc0" :hex 0xe18bc0}
   {:css "#d6d65c" :hex 0xd6d65c}
   {:css "#56d6cf" :hex 0x56d6cf}
   {:css "#ff8a5b" :hex 0xff8a5b}
   {:css "#819bff" :hex 0x819bff}
   {:css "#a8df68" :hex 0xa8df68}
   {:css "#df65e8" :hex 0xdf65e8}
   {:css "#63c4ff" :hex 0x63c4ff}
   {:css "#f2c14e" :hex 0xf2c14e}
   {:css "#72d3a6" :hex 0x72d3a6}
   {:css "#f08ca2" :hex 0xf08ca2}])

(defn color-for-index [index]
  (nth mount-colors (mod index (count mount-colors))))

(defn color-for-slot
  "Stable color identity for a mount instance; split ordinals remain distinct."
  [slot]
  (if (empty? slot)
    {:css "#9aa4af" :hex 0x9aa4af}
    (let [[mount ordinal] (last slot)
          index (+ (mod (hash mount) (count mount-colors))
                   (long (or ordinal 0)))]
      (color-for-index index))))

(defn leave
  "Invalidate all pending assembly fetches when browsing or authoring takes over."
  [state]
  (-> state (assoc :mode :browse :slots {}) (update :generation inc)))

(defn accept-event
  "Apply ordered commands only from newer server responses. Stable identical sets retain tokens."
  [state {:keys [sequence revision commands region-data]}]
  (if (or (not (number? sequence)) (<= sequence (:sequence state))
          (and (not= :assembly (:mode state)) (not-any? #(= :reset (:op %)) commands)))
    state
    (reduce
     (fn [state [index {:keys [op slot] :as command}]]
       (case op
         :reset (-> state (assoc :slots {} :mode :assembly) (update :generation inc))
         :snapshot (update state :slots select-keys (:slots command))
         :remove (update state :slots dissoc slot)
         :paint (if (get-in state [:slots slot])
                  (update-in state [:slots slot :payload]
                             (fn [payload]
                               (let [changes (:changes command) patch (:detail-delta changes)]
                                 (cond-> (merge payload (dissoc changes :detail-delta))
                                   patch (assoc :details (merge (:details payload) (dissoc patch :patch)
                                                                {:faces (delta/apply-patch (get-in payload [:details :faces]) (:patch patch))}
                                                                (when (:triangle-details (:details payload))
                                                                  (if (contains? (:patch patch) :replace)
                                                                    {:projection-reset? true :erased #{}}
                                                                    {:erased (apply disj (into (get-in payload [:details :erased] #{})
                                                                                               (get-in patch [:patch :remove]))
                                                                                    (keys (get-in patch [:patch :set])))}))))))))
                  state)
         :set (let [payload (dissoc command :op)]
                (if (= (dissoc payload :material :details :regions :layers :emission :appearance-ref :appearance-installed?) (dissoc (get-in state [:slots slot :payload]) :material :details :regions :layers :emission :appearance-ref :appearance-installed?))
                  (assoc-in state [:slots slot :payload] payload)
                  (assoc-in state [:slots slot]
                            {:payload payload :token [(:generation state) sequence index]})))
         state))
     (assoc state :sequence sequence :revision revision)
     (map-indexed vector
                  (map (fn [command]
                         (let [expand (fn [value] (if (contains? value :region-ref)
                                                    (-> value (dissoc :region-ref) (assoc :regions (get region-data (:region-ref value))))
                                                    value))]
                           (if (= :paint (:op command)) (update command :changes expand) (expand command)))) commands)))))

(defn current?
  "True only while the slot still owns this fetch token in assembly mode."
  [state slot token]
  (and (= :assembly (:mode state)) (some? token)
       (= token (get-in state [:slots slot :token]))))
