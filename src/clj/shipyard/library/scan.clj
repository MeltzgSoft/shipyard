(ns shipyard.library.scan
  "Walk the STL library and produce part records (TECHNICAL.md §5.1-§5.3).

  Cheap by construction: this stats files and reads directory names. It never
  opens a mesh - hashing 19 GB at startup is what §5.4 exists to avoid."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(def source-files
  "Variant filenames, in preference order. `supported.stl` is print scaffolding
  and is never read - it is recorded only so the UI can explain why a part it
  can see cannot be displayed."
  ["unsupported-pitted.stl" "unsupported.stl" "supported.stl"])

(def variant-key
  {"unsupported-pitted.stl" :unsupported-pitted
   "unsupported.stl"        :unsupported
   "supported.stl"          :supported})

(def renderable
  "Variants the mesh pipeline will actually open, in preference order."
  [:unsupported-pitted :unsupported])

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

(defn accepts-turrets?
  "Does this part carry sockets that a turret drops into?

  A HINT, on the same footing as `:part/role-hint` and superseded by real
  mounts: once M2 authors a socket with `:mount/accepts #{:turret}`, that is the
  fact and this is only a starting guess.

  Derived from the same names that mark turret housings - a battery or a turret
  bay is the thing with the holes. Deliberately coarse: the user reports that
  *some* batteries take turrets, lance batteries among them, and nothing in a
  folder name distinguishes those that do from those that do not. Over-flagging
  gives the mount wizard a shortlist to work through; under-flagging would hide
  the parts that need authoring most."
  [{:keys [turrets? name]}]
  (boolean (and (not turrets?) name (re-find turret-housing-re name))))

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

(defn part-folder?
  "A directory is a part folder iff it holds at least one variant file."
  [^File dir]
  (boolean (some #(.isFile (io/file dir %)) source-files)))

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
                      first
                      second)]
    {:bundle bundle :class class :weapons? weapons? :turrets? turrets?
     :name (last segments)}))

(defn- variants [^File dir]
  (into #{} (keep (fn [f] (when (.isFile (io/file dir f)) (variant-key f)))
                  source-files)))

(defn source-variant
  "The variant the mesh pipeline should open, or nil when the part ships only as
  `supported.stl` (73 such folders). Those are catalogued and flagged, never
  dropped - a library browser that hides what you own is lying to you."
  [vs]
  (some vs renderable))

(defn scan
  "Walk `root`, returning a part record per part folder.

  Directories named `other` are skipped whole: they hold Lychee projects,
  READMEs and images, not parts."
  [root]
  (let [root-file (io/file root)
        root-path (.toPath root-file)]
    (when (.isDirectory root-file)
      (->> (file-seq root-file)
           (filter #(.isDirectory ^File %))
           (remove #(= root-file %))
           (remove (fn [^File d]
                     (some (fn [s] (= "other" (str/lower-case s)))
                           (str/split (str (.relativize root-path (.toPath d)))
                                      #"[/\\]"))))
           (filter part-folder?)
           (map (fn [^File d]
                  (let [rel  (str (.relativize root-path (.toPath d)))
                        segs (str/split rel #"[/\\]")
                        info (decompose segs)
                        vs   (variants d)
                        [role src] (role-hint info)]
                    {:part/id         (str/replace rel "\\" "/")
                     :part/bundle     (:bundle info)
                     :part/class      (:class info)
                     :part/weapons?   (:weapons? info)
                     :part/turrets?   (:turrets? info)
                     :part/accepts-turrets? (accepts-turrets? info)
                     :part/name       (:name info)
                     :part/variants   vs
                     :part/source     (source-variant vs)
                     :part/renderable (some? (source-variant vs))
                     :part/role-hint  role
                     :part/role-source src})))
           (sort-by :part/id)
           vec))))
