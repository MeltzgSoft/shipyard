(ns shipyard.mount.wizard
  "Pure parsing and validation for the M2 mount wizard."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def role-options
  [:hull :hull-section :prow :bridge :antenna :engine :weapon :turret
   :stern :fin :section :detail :ordinance :terrain :unknown])

(def kind-options [:plug :socket])
(def symmetry-plane-options [:x :y :z])

(def ^:private id-re #"[A-Za-z][A-Za-z0-9_-]*")
(def ^:private unit-epsilon 1e-6)
(def ^:private orthogonal-epsilon 1e-6)
(def ^:private centerline-epsilon 1e-6)
(def ^:private plane-index {:x 0 :y 1 :z 2})

(defn- many [x]
  (cond
    (nil? x) []
    (sequential? x) x
    :else [x]))

(defn- parse-keyword [s allowed]
  (when-let [s (some-> s (str) (str/trim) (not-empty))]
    (let [k (keyword s)]
      (when (contains? (set allowed) k) k))))

(defn- parse-mount-id [s]
  (when-let [s (some-> s (str) (str/trim) (not-empty))]
    (when (re-matches id-re s)
      (keyword s))))

(defn- parse-finite-double [s]
  (try
    (let [n (Double/parseDouble (str s))]
      (when (Double/isFinite n) n))
    (catch Exception _ nil)))

(defn- checked? [x]
  (contains? #{"true" "on" "yes" "1"} (str/lower-case (str x))))

(defn- parse-edn [s]
  (try
    (edn/read-string s)
    (catch Exception _ nil)))

(defn- finite-number? [x]
  (and (number? x) (Double/isFinite (double x))))

(defn- vec3? [x]
  (and (vector? x) (= 3 (count x)) (every? finite-number? x)))

(defn- dot [[ax ay az] [bx by bz]]
  (+ (* ax bx) (* ay by) (* az bz)))

(defn- length [v] (Math/sqrt (dot v v)))

(defn- scale [s v] (mapv #(* s %) v))

(defn- v+ [a b] (mapv + a b))

(defn- normalize [v]
  (let [len (length v)]
    (when (> len 1e-12)
      (scale (/ 1.0 len) v))))

(defn valid-frame? [{:mount/keys [pos axis roll]}]
  (and (vec3? pos)
       (vec3? axis)
       (vec3? roll)
       (< (Math/abs (- 1.0 (length axis))) unit-epsilon)
       (< (Math/abs (- 1.0 (length roll))) unit-epsilon)
       (< (Math/abs (double (dot axis roll))) orthogonal-epsilon)))

(defn rotate-roll
  "Rotate `roll` around unit `axis` by `degrees`, then remove numerical drift
  along the axis and renormalize."
  [axis roll degrees]
  (let [radians (Math/toRadians (double degrees))
        c (Math/cos radians)
        s (Math/sin radians)
        rotated (v+ (scale c roll)
                    (scale s [(- (* (axis 1) (roll 2)) (* (axis 2) (roll 1)))
                              (- (* (axis 2) (roll 0)) (* (axis 0) (roll 2)))
                              (- (* (axis 0) (roll 1)) (* (axis 1) (roll 0)))]))
        without-axis (v+ rotated (scale (- (dot axis rotated)) axis))]
    (normalize without-axis)))

(defn adjusted-frame [frame roll-deg]
  (when (valid-frame? frame)
    (let [roll (rotate-roll (:mount/axis frame) (:mount/roll frame) roll-deg)]
      (when roll
        (assoc frame :mount/roll roll)))))

(defn mount-by-id [mounts id]
  (first (filter #(= id (:mount/id %)) mounts)))

(defn plug-exists? [mounts id]
  (boolean (some #(and (= :plug (:mount/kind %)) (not= id (:mount/id %))) mounts)))

(defn replace-mount [mounts mount]
  (conj (vec (remove #(= (:mount/id mount) (:mount/id %)) mounts)) mount))

(defn delete-mount [mounts id]
  (vec (remove #(= id (:mount/id %)) mounts)))

(defn suggest-mirror-id [id]
  (let [s (name id)]
    (keyword
     (cond
       (str/starts-with? s "port-") (str "starboard-" (subs s 5))
       (str/starts-with? s "starboard-") (str "port-" (subs s 10))
       :else (str s "-mirror")))))

(defn suggest-repeat-id [mounts id]
  (let [base (name id)
        [_ prefix digits] (re-matches #"^(.*?)(\d+)$" base)
        candidates (if digits
                     (map #(keyword (str prefix %))
                          (iterate inc (inc (parse-long digits))))
                     (map #(keyword (str base "-" %)) (iterate inc 2)))
        used (set (map :mount/id mounts))]
    (first (remove used candidates))))

(defn mirror-frame [frame plane offset]
  (let [idx (plane-index plane)
        reflect-pos (fn [v] (assoc v idx (- (* 2.0 offset) (v idx))))
        reflect-dir (fn [v] (update v idx -))
        mirrored {:mount/pos (reflect-pos (:mount/pos frame))
                  :mount/axis (reflect-dir (:mount/axis frame))
                  :mount/roll (reflect-dir (:mount/roll frame))}]
    (when (valid-frame? mirrored)
      mirrored)))

(defn centerline? [frame plane offset]
  (let [idx (plane-index plane)]
    (and idx (<= (Math/abs (- (double ((:mount/pos frame) idx))
                              (double offset)))
                 centerline-epsilon))))

(defn mirror-mount [mount plane offset mirror-id]
  (when-let [frame (mirror-frame (select-keys mount [:mount/pos :mount/axis :mount/roll])
                                 plane offset)]
    (merge mount
           frame
           {:mount/id mirror-id
            :mount/origin :mirrored})))

(defn repeat-values [mount mounts part-role]
  {:mount-id (some->> (:mount/id mount) (suggest-repeat-id mounts) (name))
   :kind (some-> (:mount/kind mount) (name))
   :accepts (:mount/accepts mount)
   :part-role (some-> part-role (name))})

(defn preview-values [params]
  (let [mount-id (parse-mount-id (get params "mount-id"))
        kind (parse-keyword (get params "kind") kind-options)
        part-role (parse-keyword (get params "part-role") role-options)
        accepts (set (keep #(parse-keyword % role-options) (many (get params "accepts"))))]
    (cond-> {}
      mount-id (assoc :mount-id (name mount-id))
      kind (assoc :kind kind)
      part-role (assoc :part-role part-role)
      (seq accepts) (assoc :accepts accepts))))

(defn save-request [params existing-mounts]
  (let [mount-id (parse-mount-id (get params "mount-id"))
        kind (parse-keyword (get params "kind") kind-options)
        action (parse-keyword (get params "action") [:create :replace])
        part-role (parse-keyword (get params "part-role") role-options)
        accepts (set (keep #(parse-keyword % role-options) (many (get params "accepts"))))
        roll-deg (or (parse-finite-double (get params "roll-deg")) 0.0)
        frame (adjusted-frame (parse-edn (get params "frame")) roll-deg)
        mirror? (checked? (get params "mirror"))
        repeat? (checked? (get params "repeat"))
        mirror-plane (parse-keyword (get params "mirror-plane") symmetry-plane-options)
        mirror-offset (or (parse-finite-double (get params "mirror-offset")) 0.0)
        mirror-id (or (parse-mount-id (get params "mirror-id"))
                      (some-> mount-id (suggest-mirror-id)))]
    (cond
      (nil? mount-id)
      {:error "Mount ids must start with a letter and contain only letters, numbers, dashes and underscores."}

      (nil? kind)
      {:error "Choose whether this mount is a plug or a socket."}

      (nil? part-role)
      {:error "Choose the role this part should use from now on."}

      (nil? frame)
      {:error "The selected face no longer has a valid frame. Pick it again."}

      (and (= :socket kind) (empty? accepts))
      {:error "Choose at least one role this socket accepts."}

      (and (= :create action) (mount-by-id existing-mounts mount-id))
      {:error "A mount with that id already exists. Use replace when you mean to overwrite it."}

      (and (= :plug kind) (plug-exists? existing-mounts mount-id))
      {:error "This part already has a plug. Delete it first, or replace the existing plug id."}

      (and mirror? (not= :socket kind))
      {:error "Only sockets can be mirrored."}

      (and mirror? (nil? mirror-plane))
      {:error "Choose the symmetry plane for the mirrored socket."}

      (and mirror? (nil? mirror-id))
      {:error "Choose a valid id for the mirrored socket."}

      (and mirror? (= mirror-id mount-id))
      {:error "The mirrored socket needs a different id."}

      (and mirror? (centerline? frame mirror-plane mirror-offset))
      {:error "That socket is on the symmetry plane, so it has no mirrored counterpart."}

      (and mirror? (= :create action) (mount-by-id existing-mounts mirror-id))
      {:error "A mount with the mirrored id already exists. Rename it or use replace deliberately."}

      :else
      (let [mount (cond-> {:mount/id mount-id
                           :mount/kind kind
                           :mount/pos (:mount/pos frame)
                           :mount/axis (:mount/axis frame)
                           :mount/roll (:mount/roll frame)
                           :mount/origin :picked}
                    (= :socket kind) (assoc :mount/accepts accepts))]
        (if mirror?
          (if-let [mirrored (mirror-mount mount mirror-plane mirror-offset mirror-id)]
            (let [mounts (-> existing-mounts
                             (replace-mount mount)
                             (replace-mount mirrored))]
              (cond-> {:mount mount
                       :mirrored-mount mirrored
                       :part-role part-role
                       :mounts mounts}
                repeat? (assoc :repeat-values (repeat-values mount mounts part-role))))
            {:error "The mirrored socket frame is invalid. Pick the face again."})
          (let [mounts (replace-mount existing-mounts mount)]
            (cond-> {:mount mount
                     :part-role part-role
                     :mounts mounts}
              repeat? (assoc :repeat-values (repeat-values mount mounts part-role)))))))))

(defn delete-request [params existing-mounts]
  (if-let [mount-id (parse-mount-id (get params "mount-id"))]
    {:mount-id mount-id
     :mounts (delete-mount existing-mounts mount-id)}
    {:error "Choose a mount to delete."}))
