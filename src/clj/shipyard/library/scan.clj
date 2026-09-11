(ns shipyard.library.scan
  "Walk the STL library and produce part records (TECHNICAL.md §5.1-§5.3).

  Cheap by construction: this stats files and reads directory names. It never
  opens a mesh - hashing 19 GB at startup is what §5.4 exists to avoid."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def source-files
  "Recognised variant filenames. `supported.stl` is print scaffolding
  and is never read - it is recorded only so the UI can explain why a part it
  can see cannot be displayed."
  ["unsupported.stl" "unsupported-pitted.stl" "supported.stl"])

(def variant-key
  {"unsupported-pitted.stl" :unsupported-pitted
   "unsupported.stl"        :unsupported
   "supported.stl"          :supported})

(def renderable
  "The sole source variant the mesh pipeline will open. Pitted geometry is
  retained in `:part/variants` for inventory, never used as a display mesh."
  [:unsupported])

;; --- role inference (§5.2) --------------------------------------------------
;;
;; A hint, never a fact. Measured at ~10% :unknown across the library with a
;; 10-20% error rate depending on bundle - fine for browsing, fatal for
;; compatibility matching, which reads mounts instead.

(def ^:private positional
  #"(?i)(fore|forward|front|mid|center|centre|rear|top|bottom|upper|lower)")

(def ^:private turret-re
  "A turret is a weapon *subtype*, not a sibling of one: it drops into a socket
  on a weapon battery rather than mounting on the hull. Lance batteries are the
  clearest case - the battery carries the hole, the turret fills it.

  Kept separate from the weapon lexicon so the `weapons/` directory can be
  refined rather than overridden: the directory establishes weapon-ness, the
  name says which kind."
  #"(?i)turret")

(def ^:private turret-housing-re
  "Names that mention a turret but denote the thing it mounts INTO.

  `Turret Bay 1` and `Weapon Battery Turrets` are batteries drilled to accept
  turrets - the Greater Good cruisers carry the holes. Word order is what
  separates them from real turrets: `Lancebay Turret` is a turret, `Turret Bay`
  is a bay. Size is not a usable signal and misled an earlier pass: these are
  272 KB-1.6 MB, overlapping real turrets exactly."
  #"(?i)turret\s*bay|batter(y|ies)")

(def ^:private name-rules
  "Ordered; first match wins. Matching is substring, NOT word-bounded: 14 folders
  are CamelCase or underscore-joined (`Metis_Hull`, `GGRBridge`, `VossTorpedo`,
  `BombCanon`) and word boundaries silently drop every one.

  `ram` and `aft` are the exceptions and must be bounded - as substrings they hit
  9 `Pyramid` folders and 19 `Crafty` folders respectively, and `aft` matches
  nothing real in this library at all."
  [[#"(?i)hull"                                                              :hull]
   [#"(?i)prow|nose"                                                         :prow]
   [#"(?i)bridge"                                                            :bridge]
   [#"(?i)antenna|sensor"                                                    :antenna]
   [#"(?i)engine|thruster|boosta|cowl|nozzle"                                :engine]
   ;; guarded: see turret-housing-re
   [turret-re                                                                :turret]
   [#"(?i)turret|batter(y|ie)|batery|gunz|guns|lance|torpedo|torp|launch|zzap|cannon|canon|missile|bombard|klaw|claw|blaster|\bram\b|bomb" :weapon]
   [#"(?i)stern|rudder|tail|\baft\b"                                         :stern]
   [#"(?i)wing|fin|sail"                                                     :fin]
   [#"(?i)deck|keel|pod|section|spine|dome"                                  :section]
   [#"(?i)insert|plug|logo|gargoyle"                                         :detail]])

(def escort-class?
  "Escorts are the one hull class small enough not to carry turret pits."
  #{"escort"})

(defn accepts-turrets?
  "Does this part carry pits that a turret drops into?

  A HINT, on the same footing as `:part/role-hint`, and superseded the moment
  M2 authors a socket with `:mount/accepts #{:turret}` - that is the fact, this
  is only where to start looking.

  Two sources, both coarse on purpose:

  * **Weapon batteries and turret bays**, by name. A battery is the thing with
    the holes.
  * **Hulls of cruiser class and larger**, by directory. Cruisers, light
    cruisers, grand cruisers, battleships and the single-ship capital bundles
    tend to carry dorsal turret pits; escorts do not.

  Neither can tell which individual part actually has pits - that is geometry,
  not vocabulary. Over-flagging gives the mount wizard a shortlist to work
  through; under-flagging would hide exactly the parts that most need
  authoring."
  [{:keys [turrets? class name role]}]
  (boolean
   (and (not turrets?)
        (or (and name (re-find turret-housing-re name))
            (and (#{:hull :hull-section} role)
                 (not (escort-class? (some-> class str/lower-case))))))))

(defn- hull-section?
  "A positional word adjacent to `hull` means a mandatory piece of one hull, not
  an interchangeable option. Browsing them as alternatives is a real bug: `XVI -
  Revengeful Specter` ships a one-piece hull beside front and rear halves of the
  same ship, and printing all three yields two ships' worth."
  [n]
  (boolean (or (re-find (re-pattern (str positional #"[\s_-]*hull")) n)
               (re-find (re-pattern (str #"(?i)hull[\s_-]*" positional)) n))))

(defn- negated-fin? [n]
  (boolean (re-find #"(?i)no[\s_-]+(wing|fin|sail)" n)))

(defn role-hint
  "Guess a part's role. Returns `[role source]`; source is `:class` when a
  directory told us and `:inferred` when only the name did."
  [{:keys [class weapons? turrets? name]}]
  (let [c (some-> class str/lower-case)]
    (cond
      ;; A turrets/ directory is a fact, like weapons/. Turrets were moved there
      ;; deliberately (TURRET-MOVES.json in the library) precisely so this stops
      ;; being a name guess.
      turrets?            [:turret :class]

      ;; A prow sold with a weapon fitted is still a prow. 26 folders live under
      ;; weapons/ with "prow" in the name - "Stalker Prow 1 with Lance Turret",
      ;; "GGDF Diplomat Torpedo Prow A" - and every one of them is a prow, with
      ;; no counter-example in the library. This is FP-2 from the role-inference
      ;; spike, where the directory overrode a name that was more specific.
      (and weapons? (re-find #"(?i)prow|nose" name))
      [:prow :inferred]

      ;; Otherwise the directory says weapon and the name may refine it: a
      ;; turret is a weapon subtype, so this narrows rather than contradicts.
      (and weapons? (re-find turret-re name) (not (re-find turret-housing-re name)))
      [:turret :inferred]

      weapons?            [:weapon :class]
      (= c "ordinance")   [:ordinance :class]
      (= c "terrain")     [:terrain :class]
      (escort-class? c)    [:unknown :inferred]
      (hull-section? name) [:hull-section :inferred]
      :else
      (or (some (fn [[re role]]
                  (when (re-find re name)
                    (when-not (or (and (= role :fin) (negated-fin? name))
                                  (and (= role :turret) (re-find turret-housing-re name)))
                      [role :inferred])))
                name-rules)
          [:unknown :inferred]))))

;; --- part folder discovery (§5.1) -------------------------------------------

(defn part-folder?!
  "A directory is a part folder iff it holds at least one variant file."
  [dir]
  (boolean (some #(fs/regular-file? (fs/file dir %)) source-files)))

(defn- decompose
  "Split a library-relative path into bundle, optional class, and weapons flag.

  `<Bundle>/[<Class>/][weapons/[turrets/]]<Part Name>/`. Class is absent in the
  four single-ship bundles."
  [segments]
  (let [bundle   (first segments)
        middle   (butlast (rest segments))
        lower    (map str/lower-case middle)
        weapons? (boolean (some #{"weapons"} lower))
        turrets? (boolean (some #{"turrets"} lower))
        class    (->> (map vector lower middle)
                      (remove (comp #{"weapons" "turrets"} first))
                      (first)
                      (second))]
    {:bundle bundle :class class :weapons? weapons? :turrets? turrets?
     :name (last segments)}))

(defn- variants! [dir]
  (into #{} (keep (fn [f] (when (fs/regular-file? (fs/file dir f)) (variant-key f)))
                  source-files)))

(defn source-variant
  "The unpitted unsupported variant the mesh pipeline should open, or nil when
  it is absent. Pitted-only and supported-only folders are catalogued and
  flagged, never dropped - a library browser that hides what you own is lying."
  [vs]
  (some vs renderable))

(defn scan!
  "Walk `root`, returning a part record per part folder.

  Directories named `other` are skipped whole: they hold Lychee projects,
  READMEs and images, not parts."
  [root]
  (when (fs/directory? root)
    (->> (fs/glob root "**" {:hidden false})
         (filter fs/directory?)
         (remove (fn [d]
                   (some #(= "other" (str/lower-case (str %)))
                         (fs/components (fs/relativize root d)))))
         (filter part-folder?!)
         (map (fn [d]
                (let [rel  (fs/relativize root d)
                      segs (mapv str (fs/components rel))
                      info (decompose segs)
                      vs   (variants! d)
                      [role src] (role-hint info)]
                  {:part/id         (str/join "/" segs)
                   :part/bundle     (:bundle info)
                   :part/class      (:class info)
                   :part/weapons?   (:weapons? info)
                   :part/turrets?   (:turrets? info)
                   :part/accepts-turrets? (accepts-turrets? (assoc info :role role))
                   :part/name       (:name info)
                   :part/variants   vs
                   :part/source     (source-variant vs)
                   :part/renderable (some? (source-variant vs))
                   :part/role-hint  role
                   :part/role-source src})))
         (sort-by :part/id)
         vec)))
