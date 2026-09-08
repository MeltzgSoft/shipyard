(ns shipyard.mount.wizard
  "Pure parsing and validation for the M2 mount wizard."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [shipyard.geom :as geom]
            [shipyard.math :as math]
            [shipyard.mount.split :as split]
            [shipyard.part.orientation :as orientation]))

(def role-options
  [:hull :hull-section :prow :bridge :antenna :engine :weapon :turret
   :stern :fin :section :detail :ordinance :terrain :unknown])

(def kind-options [:plug :socket])
(def symmetry-plane-options [:x :y :z])

(def ^:private id-re #"[A-Za-z][A-Za-z0-9_-]*")
(def ^:private centerline-epsilon 1e-6)

(defn- many [x]
  (cond
    (nil? x) []
    (sequential? x) x
    :else [x]))

(defn- parse-keyword [s allowed]
  (when-let [s (some-> s (str) (str/trim) (not-empty))]
    (let [k (keyword s)]
      (when (contains? (set allowed) k) k))))

(defn acceptance-profiles
  "The one choice a socket author makes. Hulls may expose the single shared
  hardpoint profile; weapons are deliberately limited to turret pits."
  [part-role]
  (let [singleton-profiles (mapv (fn [role] {:id role :label (name role) :accepts #{role}})
                                 role-options)]
    (cond
      (= :weapon part-role)
      [{:id :turret :label "Turret pit" :accepts #{:turret}}]

      (#{:hull :hull-section} part-role)
      (conj singleton-profiles {:id :turret-or-antenna
                                :label "Turret or antenna hardpoint"
                                :accepts #{:turret :antenna}})

      :else singleton-profiles)))

(defn- accepted-roles [params part-role]
  (let [values (many (get params "accepts"))
        profiles (into {} (map (juxt :id identity) (acceptance-profiles part-role)))]
    (if (and (= 1 (count values)) (string? (first values)))
      (or (:accepts (get profiles (keyword (first values))))
          #{})
      (set (keep #(parse-keyword % role-options) values)))))

(defn- accepted-by-host? [part-role accepts]
  (contains? (set (map :accepts (acceptance-profiles part-role))) accepts))

(defn- acceptance-error [part-role]
  (case part-role
    :weapon "Weapon sockets can accept only turrets."
    (:hull :hull-section) "Choose one role or the Turret or antenna hardpoint profile."
    "Choose exactly one role this socket accepts."))

(defn- parse-mount-id [s]
  (when-let [s (some-> s (str) (str/trim) (not-empty))]
    (when (re-matches id-re s)
      (keyword s))))

(defn- parse-positive-long [s]
  (try
    (let [n (Long/parseLong (str/trim (str s)))]
      (when (pos? n) n))
    (catch Exception _ nil)))

(defn- checked? [x]
  (contains? #{"true" "on" "yes" "1"} (str/lower-case (str x))))

(defn- parse-edn [s]
  (try
    (edn/read-string s)
    (catch Exception _ nil)))

(defn- vec3? [x]
  (and (vector? x) (= 3 (count x)) (every? math/finite-number? x)))

(defn- fallback-roll [axis]
  (some #(math/normalize (math/project-onto-plane axis %) 1e-12)
        [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]))

(defn- normalize-frame [{:mount/keys [pos axis roll] :as frame}]
  (when (and (vec3? pos) (vec3? axis))
    (when-let [axis (math/normalize axis 1e-12)]
      (when-let [roll (or (when (vec3? roll)
                            (math/normalize
                             (math/project-onto-plane axis roll)
                             1e-12))
                          (fallback-roll axis))]
        (assoc frame :mount/axis axis :mount/roll roll)))))

(defn valid-frame? [{:mount/keys [pos axis roll]}]
  (geom/valid-frame? {:mount/pos pos :mount/axis axis :mount/roll roll}))

(defn rotate-roll
  "Rotate `roll` around unit `axis` by `degrees`, then remove numerical drift
  along the axis and renormalize."
  [axis roll degrees]
  (let [radians (Math/toRadians (double degrees))
        c (Math/cos radians)
        s (Math/sin radians)
        rotated (math/add (math/scale c roll)
                          (math/scale s (math/cross axis roll)))
        without-axis (math/project-onto-plane axis rotated)]
    (math/normalize without-axis 1e-12)))

(defn adjusted-frame [frame roll-deg]
  (when-let [frame (normalize-frame frame)]
    (let [roll (rotate-roll (:mount/axis frame) (:mount/roll frame) roll-deg)]
      (when roll
        (assoc frame :mount/roll roll)))))

(defn mount-by-id [mounts id]
  (first (filter #(= id (:mount/id %)) mounts)))

(defn plug-exists? [mounts]
  (boolean (some #(= :plug (:mount/kind %)) mounts)))

(defn replace-mount [mounts mount]
  (conj (vec (remove #(= (:mount/id mount) (:mount/id %)) mounts)) mount))

(defn replace-mount-by-id [mounts old-id mount]
  (conj (vec (remove #(= old-id (:mount/id %)) mounts)) mount))

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

(defn mirror-frame
  ([frame plane offset]
   (mirror-frame frame plane offset orientation/identity-quaternion))
  ([frame plane offset part-orientation]
   (let [mirrored {:mount/pos (orientation/reflect-position
                               part-orientation plane offset (:mount/pos frame))
                   :mount/axis (orientation/reflect-direction
                                part-orientation plane (:mount/axis frame))
                   :mount/roll (orientation/reflect-direction
                                part-orientation plane (:mount/roll frame))}]
     (when (valid-frame? mirrored)
       mirrored))))

(defn centerline?
  ([frame plane offset]
   (centerline? frame plane offset orientation/identity-quaternion))
  ([frame plane offset part-orientation]
   (when-let [distance (orientation/plane-distance
                        part-orientation plane offset (:mount/pos frame))]
     (<= (Math/abs (double distance)) centerline-epsilon))))

(defn mirror-mount
  ([mount plane offset mirror-id]
   (mirror-mount mount plane offset mirror-id orientation/identity-quaternion))
  ([mount plane offset mirror-id part-orientation]
   (when-let [frame (mirror-frame
                     (select-keys mount [:mount/pos :mount/axis :mount/roll])
                     plane offset part-orientation)]
     (cond-> (merge mount frame {:mount/id mirror-id :mount/origin :mirrored})
       (:mount/split mount)
       (update-in [:mount/split :bounds]
                  (fn [[[xmin ymin] [xmax ymax]]]
                    ;; Reflection preserves X; reconstructing right-handed Y reverses it.
                    [[xmin (- ymax)] [xmax (- ymin)]]))))))

(defn repeat-values [mount mounts]
  (cond-> {:mount-id (some->> (:mount/id mount) (suggest-repeat-id mounts) (name))
           :kind (some-> (:mount/kind mount) (name))
           :accepts (:mount/accepts mount)
           :capacity (:mount/capacity mount)}
    (:mount/split mount) (assoc :split-direction (get-in mount [:mount/split :direction]))))

(defn preview-values
  ([params] (preview-values params nil))
  ([params part-role]
   (let [mount-id (parse-mount-id (get params "mount-id"))
         kind (parse-keyword (get params "kind") kind-options)
         accepts (accepted-roles params part-role)
         capacity (parse-positive-long (get params "capacity"))
         twist-deg (or (get params "twist-deg") (get params "roll-deg"))]
     (cond-> {}
       mount-id (assoc :mount-id (name mount-id))
       kind (assoc :kind kind)
       capacity (assoc :capacity capacity)
       (get params "split-direction") (assoc :split-direction (keyword (get params "split-direction")))
       twist-deg (assoc :twist-deg twist-deg)
       (seq accepts) (assoc :accepts accepts)))))

(defn mount-values [mount]
  (cond-> {:mount-id (some-> (:mount/id mount) (name))
           :kind (:mount/kind mount)}
    (seq (:mount/accepts mount)) (assoc :accepts (set (:mount/accepts mount)))
    (:mount/capacity mount) (assoc :capacity (:mount/capacity mount))
    (:mount/split mount) (assoc :split-direction (get-in mount [:mount/split :direction]))))

(defn mount-frame [mount]
  (normalize-frame (select-keys mount [:mount/pos :mount/axis :mount/roll :mount/split])))

(defn edit-request [params existing-mounts]
  (let [mount-id (parse-mount-id (get params "mount-id"))
        mount (mount-by-id existing-mounts mount-id)
        frame (mount-frame mount)]
    (cond
      (nil? mount-id)
      {:error "Choose a mount to edit."}

      (nil? mount)
      {:error "No mount with that id exists."}

      (nil? frame)
      {:error "That mount no longer has a valid frame. Pick the face again."}

      :else
      {:mount mount
       :original-mount-id mount-id
       :frame frame
       :values (mount-values mount)})))

(defn part-role-request [params]
  (if-let [role (parse-keyword (get params "part-role") role-options)]
    {:part-role role}
    {:error "Choose the role this part should use from now on."}))

(defn error-preview [part params error]
  (let [frame (normalize-frame (parse-edn (get params "frame")))
        original-mount-id (parse-mount-id (get params "original-mount-id"))]
    (cond-> {:part part
             :values (preview-values params (:part/role-hint part))
             :error error}
      frame (assoc :frame frame)
      original-mount-id (assoc :mode :edit
                               :original-mount-id original-mount-id))))

(defn save-request
  ([params existing-mounts]
   (save-request params existing-mounts orientation/identity-quaternion nil))
  ([params existing-mounts part-orientation]
   (save-request params existing-mounts part-orientation nil))
  ([params existing-mounts part-orientation part-role]
   (let [mount-id (parse-mount-id (get params "mount-id"))
         original-mount-id (parse-mount-id (get params "original-mount-id"))
         kind (parse-keyword (get params "kind") kind-options)
         action (parse-keyword (get params "action") [:create :replace :update])
         accepts (accepted-roles params part-role)
         capacity (or (parse-positive-long (get params "capacity")) 1)
         twist-deg (or (math/parse-finite-double (or (get params "twist-deg")
                                                     (get params "roll-deg")))
                       0.0)
         source-frame (parse-edn (get params "frame"))
         frame (adjusted-frame source-frame twist-deg)
         split-direction (parse-keyword (or (get params "split-direction") "vertical") [:vertical :horizontal])
         split-data (when frame (split/metadata-for source-frame frame split-direction))
         update? (= :update action)
         base-id (when update? original-mount-id)
         existing-base (when base-id (mount-by-id existing-mounts base-id))
         other-mounts (if base-id
                        (remove #(= base-id (:mount/id %)) existing-mounts)
                        existing-mounts)
         mirror? (checked? (get params "mirror"))
         repeat? (checked? (get params "repeat"))
         mirror-plane (parse-keyword (get params "mirror-plane") symmetry-plane-options)
         mirror-offset (or (math/parse-finite-double (get params "mirror-offset")) 0.0)
         mirror-id (or (parse-mount-id (get params "mirror-id"))
                       (some-> mount-id (suggest-mirror-id)))]
     (cond
       (nil? mount-id)
       {:error "Mount ids must start with a letter and contain only letters, numbers, dashes and underscores."}

       (nil? kind)
       {:error "Choose whether this mount is a plug or a socket."}

       (nil? action)
       {:error "Choose whether to save, replace, or update this mount."}

       (nil? frame)
       {:error "The selected face no longer has a valid frame. Pick it again."}

       (and update? (nil? original-mount-id))
       {:error "Choose a mount to edit."}

       (and update? (nil? existing-base))
       {:error "No mount with that id exists."}

       (and (= :socket kind) (not (accepted-by-host? part-role accepts)))
       {:error (acceptance-error part-role)}

       (and (= :socket kind) (contains? params "capacity") (nil? (parse-positive-long (get params "capacity"))))
       {:error "Capacity must be a whole number of at least 1."}

       (and (= :socket kind) (> capacity 256))
       {:error "Capacity cannot exceed 256 sections."}

       (and (= :socket kind) (> capacity 1) (nil? split-data))
       {:error "Pick the socket face again and choose a vertical or horizontal split."}

       (and (= :create action) (mount-by-id existing-mounts mount-id))
       {:error "A mount with that id already exists. Use replace when you mean to overwrite it."}

       (and update? (mount-by-id other-mounts mount-id))
       {:error "A mount with that id already exists. Rename it or use replace deliberately."}

       (and (= :plug kind) (plug-exists? other-mounts))
       {:error "This part already has a plug. Delete it first, or replace the existing plug id."}

       (and mirror? (not= :socket kind))
       {:error "Only sockets can be mirrored."}

       (and mirror? (nil? mirror-plane))
       {:error "Choose the symmetry plane for the mirrored socket."}

       (and mirror? (nil? mirror-id))
       {:error "Choose a valid id for the mirrored socket."}

       (and mirror? (= mirror-id mount-id))
       {:error "The mirrored socket needs a different id."}

       (and mirror? (centerline? frame mirror-plane mirror-offset part-orientation))
       {:error "That socket is on the symmetry plane, so it has no mirrored counterpart."}

       (and mirror? (= :create action) (mount-by-id existing-mounts mirror-id))
       {:error "A mount with the mirrored id already exists. Rename it or use replace deliberately."}

       :else
       (let [mount (cond-> {:mount/id mount-id
                            :mount/kind kind
                            :mount/pos (:mount/pos frame)
                            :mount/axis (:mount/axis frame)
                            :mount/roll (:mount/roll frame)
                            :mount/origin (or (:mount/origin existing-base) :picked)}
                     (= :socket kind) (assoc :mount/accepts accepts
                                             :mount/capacity capacity)
                     (and (= :socket kind) (> capacity 1)) (assoc :mount/split split-data))]
         (if mirror?
           (if-let [mirrored (mirror-mount mount mirror-plane mirror-offset mirror-id
                                           part-orientation)]
             (let [mounts (-> existing-mounts
                              (replace-mount-by-id (or base-id mount-id) mount)
                              (replace-mount mirrored))]
               (cond-> {:mount mount
                        :mirrored-mount mirrored
                        :mounts mounts}
                 repeat? (assoc :repeat-values (repeat-values mount mounts))))
             {:error "The mirrored socket frame is invalid. Pick the face again."})
           (let [mounts (if base-id
                          (replace-mount-by-id existing-mounts base-id mount)
                          (replace-mount existing-mounts mount))]
             (cond-> {:mount mount
                      :mounts mounts}
               repeat? (assoc :repeat-values (repeat-values mount mounts))))))))))

(defn delete-request [params existing-mounts]
  (if-let [mount-id (parse-mount-id (get params "mount-id"))]
    {:mount-id mount-id
     :mounts (delete-mount existing-mounts mount-id)}
    {:error "Choose a mount to delete."}))
