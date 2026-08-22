# Shipyard - Technical Specification

Implementation-level design. Scope: **M1** (library scan, catalog, mesh pipeline,
single-part viewer) in full detail, plus the system-wide foundations M1 forces us to
commit to - project layout, dependencies, storage, and the HTTP contract - which every
later milestone inherits.

M2-M6 are deliberately not specced at this depth. M1 will teach us things about the mesh
pipeline that would invalidate the guesses.

Companion to [SPEC.md](SPEC.md), which covers product scope and architecture rationale.

---

## 1. Resolved decisions

Three questions SPEC.md left open, now settled.

### 1.1 No glTF - a purpose-built wire format

SPEC §7 said encode to `.glb`. Dropped. We control both ends of the wire, so glTF's
interop value is close to zero here, while writing a GLB encoder on the JVM means a JSON
chunk, a BIN chunk, accessor/bufferView bookkeeping, and no mainstream Clojure library to
lean on.

Instead: a flat binary format (§6) that decodes straight into a three.js
`BufferGeometry` with no parsing beyond typed-array views over the received
`ArrayBuffer`. Roughly 40 lines to write, 15 to read.

The encoder lives behind `shipyard.mesh.wire` alone. If interop ever matters, swapping in
GLB touches one namespace.

### 1.2 Datascript for querying - it does not replace the sidecars

**Datascript ingests at startup. It never owns data.**

Datascript is in-memory and non-durable; a process restart loses everything in it. So it
can only be a derived index. The durable layer is plain EDN files.

```
   DURABLE (source of truth)                    DERIVED (rebuildable, disposable)
   ─────────────────────────                    ────────────────────────────────
   <part folder>/shipyard.edn   ─── scan ──┐
     mounts, per-part overrides            │
                                           ├──▶  Datascript DB    (in memory)
   $XDG_DATA_HOME/shipyard/                │       parts, mounts, loadouts,
     loadouts.edn  fleets.edn  schemes.edn ┘       fleets, schemes - all queryable

   $XDG_CACHE_HOME/shipyard/
     index.edn        scan cache, mtime+size keyed
     mesh/<sha>.symesh  encoded meshes
```

**Read path.** At startup, scan the library, read every `shipyard.edn`, read the user
data files, transact the lot into a fresh Datascript DB. 1,661 small EDN files is a fast
read; no mesh is touched (§5.3).

**What each layer may hold - and what never moves.**

| Layer | Holds | Never holds |
|---|---|---|
| Datascript | Metadata only: ids, names, roles, variants, a triangle count, a content hash, mount frames | Any geometry. Not one vertex |
| `$XDG_CACHE_HOME/…/mesh/` | Derived `.symesh` encodings, regenerable from source at any time | Copies of STLs |
| The library | The STLs, exactly where they are | - |

**Source STLs are never copied, moved, or ingested.** They are opened lazily, once, when
a part is first viewed, and read again only if their mtime or size changes. The full
library at rest is untouched by Shipyard; deleting the entire cache costs nothing but
recomputation.

The naming invites a misreading worth heading off: `:part/mesh-key` is the SHA-256 *of*
the source STL, used to name the derived file `mesh/<sha>.symesh`. The STL itself is not
stored under that hash - the hash is an identity for the encoding produced from it.

**Write path is write-through, file first.**

```clojure
(defn save-mount! [part-id mount]
  (sidecar/update! part-id #(update % :mounts conj mount))  ; 1. durable write
  (d/transact! conn [(mount->tx part-id mount)]))           ; 2. index update
```

File first matters: if the transact throws, the data is already safe on disk and the next
restart picks it up. The reverse order can lose a write.

**Why Datascript earns its place** even though M1's queries are simple: compatibility
filtering in M3 is a genuine join - every part whose role satisfies some socket's
`accepts`, within a class, excluding those already slotted. That's a datalog one-liner
and an awkward nest of `filter` over maps. Establishing it in M1 avoids a migration.

**Why not Datalevin**, which would be durable and remove the ingest step: it would make a
database the source of truth for data that wants to live beside the STLs - greppable,
diffable, and portable if the library moves. Sidecars keep mounts attached to the parts
they describe. The ingest step is the price and it is small.

### 1.3 Mounts live in the part folder

```
Human Navy Fleet Bundle/Cruiser/Hull/
    unsupported.stl
    unsupported-pitted.stl
    supported.stl
    shipyard.edn          ← mounts for this part
```

```clojure
{:shipyard/version 1
 :part/role   :hull
 :mounts [{:mount/id :prow  :mount/kind :socket :mount/accepts #{:prow}
           :mount/pos [0.0 0.0 86.77] :mount/axis [0.0 0.0 1.0] :mount/roll [0.0 1.0 0.0]
           :mount/origin :picked}]}
```

Consequences: mounts survive library reorganisation, diff per-part rather than as one
churning central file, and are trivially inspectable. The library becomes
self-describing - a copied part folder carries its own mount data.

---

## 2. Project layout

**Source roots are split by runtime.** A single `src/` would put `.cljs` files on the JVM
classpath and ship them inside the uberjar - dead weight that also blurs which code runs
where. Splitting makes the boundary a build fact rather than a naming convention.

```
shipyard/
├── deps.edn                        :paths ["src/clj" "src/cljc" "resources"]
├── shadow-cljs.edn                 :source-paths ["src/cljs" "src/cljc"]
├── build.clj                       tools.build: npm, shadow release, uberjar
├── package.json / package-lock.json    pinned three.js + htmx
├── node_modules/                   GITIGNORED
├── SPEC.md  TECHNICAL.md
│
├── src/clj/shipyard/               JVM only
│   ├── main.clj                    entry point: read config, ig/init, shutdown hook
│   ├── system.clj                  integrant key derivation, halt ordering
│   ├── library/{scan,index}.clj    part-folder discovery; mtime+size scan cache
│   ├── catalog/{db,sidecar}.clj    datascript conn + schema; shipyard.edn read/write
│   ├── mesh/{stl,weld,lod,cache}.clj
│   └── http/{routes,views,htmx}.clj
│
├── src/cljc/shipyard/              BOTH runtimes
│   ├── wire.cljc                   .symesh layout - encode JVM / decode CLJS
│   └── geom.cljc                   mount frames, assembly transform, mirroring
│
├── src/cljs/shipyard/              browser only
│   └── viewport.cljs               the island
│
├── resources/
│   ├── config.edn                  system configuration (§2.1)
│   └── public/
│       ├── app.css
│       └── js/                     GITIGNORED - shadow-cljs output + htmx copy
│
└── test/
    ├── clj/shipyard/               unit + integration + e2e (§10)
    ├── cljc/shipyard/wire_test.cljc    runs on BOTH runtimes
    └── fixtures/                   small generated STLs, committed
```

`geom.cljc` is new and belongs in `cljc` for the same reason `wire.cljc` does: the
assembly transform (SPEC §5.3) is computed server-side to place parts, and the viewport
needs the same maths for the M2 wizard's live gizmo preview. One definition, not two.

### 2.1 Configuration

**Configuration is data, not code.** `resources/config.edn` holds the integrant system map;
no namespace exists to hold constants.

```clojure
;; resources/config.edn - read with aero, which supplies #env / #or / #profile
{:shipyard/library    {:root #or [#env SHIPYARD_LIBRARY #ref [:xdg :data-home]]}
 :shipyard/catalog    {:library #ig/ref :shipyard/library}
 :shipyard/mesh-cache {:dir       #ref [:xdg :cache-home]
                       :cap-bytes #profile {:default 4294967296 :test 67108864}
                       :threads   #or [#env SHIPYARD_THREADS :auto]
                       :crease-deg 35
                       :lod-tiers [1.0 0.25 0.05]}
 :shipyard/http       {:port    #or [#env PORT 8080]
                       :catalog #ig/ref :shipyard/catalog
                       :cache   #ig/ref :shipyard/mesh-cache}}
```

Each component namespace defines its own `ig/init-key` and `ig/halt-key!`, keeping
lifecycle next to the thing it constructs. `system.clj` holds only key derivation and
anything ordering-sensitive; `main.clj` reads the config, calls `ig/init`, and registers a
shutdown hook.

**Three layers, later winning over earlier:**

1. `resources/config.edn` - defaults, shipped in the jar.
2. `$XDG_CONFIG_HOME/shipyard/config.edn` - user preferences, deep-merged if present.
3. Environment variables - `SHIPYARD_LIBRARY`, `PORT`, `SHIPYARD_THREADS`.

The middle layer matters because **the library root is user data, not deployment
configuration**. Which directory holds someone's models is a preference they set once and
change rarely, so it belongs in a file they own rather than an env var they must remember
to export.

Tunables that spikes established live here rather than being hardcoded: the crease angle
(§6.2), the LOD tier ratios (§6.3), and the cache cap (§6.5). All three were measured
rather than derived, so all three should be adjustable without a rebuild.

Aero's `#profile` gives tests a small cache cap so eviction is exercisable in seconds.

## 3. Dependencies

```clojure
;; deps.edn
{:paths ["src/clj" "src/cljc" "resources"]
 :deps
 {org.clojure/clojure            {:mvn/version "1.12.0"}
  metosin/reitit-ring            {:mvn/version "0.7.2"}
  ring/ring-jetty-adapter        {:mvn/version "1.12.2"}
  hiccup/hiccup                  {:mvn/version "2.0.0-RC3"}
  datascript/datascript          {:mvn/version "1.7.3"}
  integrant/integrant            {:mvn/version "1.0.1"}
  aero/aero                      {:mvn/version "1.1.6"}
  org.clojure/tools.logging      {:mvn/version "1.3.0"}
  org.lwjgl/lwjgl                {:mvn/version "3.3.6"}
  org.lwjgl/lwjgl-meshoptimizer  {:mvn/version "3.3.6"}}
 :aliases
 {:natives-linux   {:extra-deps {org.lwjgl/lwjgl$natives-linux               {:mvn/version "3.3.6"}
                                 org.lwjgl/lwjgl-meshoptimizer$natives-linux {:mvn/version "3.3.6"}}}
  :natives-windows {:extra-deps {org.lwjgl/lwjgl$natives-windows               {:mvn/version "3.3.6"}
                                 org.lwjgl/lwjgl-meshoptimizer$natives-windows {:mvn/version "3.3.6"}}}
  :natives-macos   {…}  ; natives-macos, natives-macos-arm64, natives-linux-arm64
  :build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.5"}} :ns-default build}
  :cljs  {:extra-paths ["src/cljs" "src/cljc"]
          :extra-deps {thheller/shadow-cljs {:mvn/version "3.4.12"}}}
  :test  {:extra-paths ["test/clj" "test/cljc"]
          :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}
                       etaoin/etaoin       {:mvn/version "1.1.43"}}}}}
```

**Verified end to end** (issue #6): natives load, all calls execute, results are
deterministic across threads. `MESHOPTIMIZER_VERSION = 220`.

tools.deps expresses a Maven classifier as `artifact$classifier`, **not** a `:classifier`
key - the latter resolves to the wrong artifact silently. Core `lwjgl` natives are
required alongside the module's, not merely the module's. Five classifiers resolve:
`natives-linux`, `natives-windows`, `natives-macos`, `natives-macos-arm64`,
`natives-linux-arm64`. The uberjar bundles all of them; LWJGL selects at runtime.

**Java 25 requires two JVM flags:**

```
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
```

Both are warnings only on 25, but native access becomes a hard error in a later JDK, so
set them now. For the shipped uberjar use the `Enable-Native-Access: ALL-UNNAMED`
manifest attribute instead of the CLI flag.

LWJGL extracts natives to a temp directory at startup, so **the runtime needs a writable
temp dir** - override with `-Dorg.lwjgl.librarypath` where that does not hold. Its
`Failed to instantiate memory allocator: JEmallocAllocator` log line is harmless; it
falls back to the stdlib allocator.

No CSG dependency - face picking (SPEC §5) removed the need, which is what makes the pure
JVM stack viable.

---

## 4. Datascript schema

```clojure
(def schema
  {:part/id          {:db/unique :db.unique/identity}   ; relative folder path
   :part/bundle      {:db/index true}
   :part/class       {:db/index true}                   ; :cruiser, :escort, nil
   :part/role-hint   {:db/index true}                   ; browsing only - never compatibility (§5.2)
   :part/role-source {}                                 ; :inferred | :class | :manual
   :part/name        {}
   :part/variants    {:db/cardinality :db.cardinality/many}  ; :supported :unsupported :unsupported-pitted
   :part/mesh-key    {}                                 ; sha256 of chosen STL, nil until preprocessed
   :part/tris        {}
   :part/mounts      {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref
                      :db/isComponent true}

   :mount/id         {}
   :mount/kind       {:db/index true}                   ; :socket | :plug
   :mount/accepts    {:db/cardinality :db.cardinality/many}
   :mount/pos        {} :mount/axis {} :mount/roll {}
   :mount/origin     {}

   :loadout/id       {:db/unique :db.unique/identity}
   :loadout/hull     {:db/valueType :db.type/ref}
   :loadout/slots    {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref
                      :db/isComponent true}
   :slot/mount-id    {} :slot/part {:db/valueType :db.type/ref}

   :fleet/id         {:db/unique :db.unique/identity}
   :fleet/loadouts   {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :scheme/id        {:db/unique :db.unique/identity}})
```

`:part/id` is the library-relative folder path - stable, human-readable, debuggable in a
URL. Distinct from `:part/mesh-key`, the content hash used for mesh caching (§5.4).

The M1 query surface is small, but the shape it establishes is what M3 needs:

```clojure
;; M1: browse
(d/q '[:find ?id ?name :in $ ?bundle ?class
       :where [?e :part/bundle ?bundle] [?e :part/class ?class]
              [?e :part/id ?id] [?e :part/name ?name]]
     @conn "Human Navy Fleet Bundle" :cruiser)

;; M3: what can go in this socket - the join that justifies datascript
(d/q '[:find ?id :in $ ?class [?role ...]
       :where [?e :part/class ?class] [?e :part/role ?role] [?e :part/id ?id]]
     @conn :cruiser (:mount/accepts socket))
```

---

## 5. Library scan

### 5.1 Part-folder discovery

A directory is a part folder iff it contains at least one of `unsupported.stl`,
`unsupported-pitted.stl`, `supported.stl`. Directories named `other` are skipped whole.

Path decomposition, relative to library root:

```
<Bundle>/[<Class>/][weapons/]<Part Name>/
```

- **bundle** - first segment.
- **class** - second segment if present and not `weapons`/the part folder itself. Absent
  in the four single-ship bundles.
- **weapons** - presence of a `weapons` segment.
- **name** - the part folder's own name.

### 5.2 Role inference - a hint, never a fact

**Measured over all 1,661 part folders (issue #5).** The rule table works far better than
feared on its headline number and far worse on inspection, and the design follows from
the second fact rather than the first.

Raw result: `:unknown` is **25.1%**, not the ~60% we braced for. But that 74.9%
"coverage" is inflated. 22.8 points of it come from directory facts, not filenames - a
`weapons/` path segment or an `ordinance` class - which are free and correct. Filename
matching alone classifies **52.1%**, and after removing demonstrable false positives,
roughly **45% of the library carries a filename-derived role you could defend.**

**The variance across bundles is the real finding.** This is nineteen designers'
vocabularies, not one library:

| bundle | `:unknown` |
|---|---:|
| Human Navy Fleet Bundle | 1.6% |
| Pirate Space Elves | 6.4% |
| Toaster Mechanics | 17.3% |
| Zombie Space Raider | 45.8% |
| Ork Fleet Bundle | 54.9% |
| Space Bugs Fleet Bundle | 69.2% |

Human Navy scores 1.6% because that designer puts "Prow" in nearly every escort folder
name - and those are not prows (see below). The table is tuned to Human Navy vocabulary
and degrades monotonically with distance from it.

#### Three failure classes no regex can fix

These are why role must never be authoritative.

**Whole ships wearing a part name.** Human Navy's `Escort/` holds 32 folders matching
`prow` and **zero hulls** - there is nothing for `Cyanide Prow Python` to attach to,
because it *is* the Python escort; "Cyanide Prow" is the styling. The identical object is
classified three ways depending on who named the folder:

| object | folder | inferred |
|---|---|---|
| Python escort | `Human Navy/Escort/Cyanide Prow Python` | `:prow` |
| Python escort | `Hazard Stripe/Escort/IW Python` | `:unknown` |
| Python escort | `Toaster Mechanics/Escort/Toaster Python` | `:unknown` |

45 parts affected. The table measures vocabulary, not geometry.

**Hull sections indistinguishable from whole hulls.** 35 of 171 `:hull` matches are
mandatory pieces of one hull, not interchangeable options - `Bloody Iron Forward hull` +
`Rear Hull`, `Unbreakable Speculation - Mid/Center/Rear Hull`. Worst case, `XVI -
Revengeful Specter` has `- hull`, `- multi part hull front` and `- multi part hull rear`:
the first is a one-piece print, the others are the same ship split for small printers.
Printing all three yields two ships' worth of hull. And bare numbering is genuinely
ambiguous - `Combatbarge Hull 1/Hull 2` are sections while `Pirate Elves Hull1/Hull2/Hull3`
are alternatives, with nothing in the name to separate them.

**Folder names that enumerate fitted options.** `Blitz Deck 2 Supa boosta small` is a
10.6 MB whole Ork escort whose name lists its loadout. Any lexicon grabs it. There is no
syntax distinguishing "part named X" from "ship fitted with X", so every rule table over
these names carries an irreducible error rate.

#### The revised table

Ordered, first match wins, case-insensitive, matched against the folder name.

| Rule | Role |
|---|---|
| `weapons` path segment | `:weapon` |
| class segment is `ordinance` | `:ordinance` |
| class segment is `Terrain` | `:terrain` |
| positional word (`fore|forward|front|mid|center|rear|top|bottom|upper|lower`) adjacent to `hull` | `:hull-section` |
| `hull` | `:hull` |
| `prow|nose` | `:prow` |
| `bridge` | `:bridge` |
| `antenna|sensor` | `:antenna` |
| `engine|thruster|boosta|cowl|nozzle` | `:engine` |
| `turret|batter(y|ie)|batery|gunz|guns|lance|torpedo|torp|launch|zzap|cannon|canon|missile|bombard|klaw|claw|blaster|\bram\b|bomb` | `:weapon` |
| `stern|rudder|tail|\baft\b` | `:stern` |
| `wing|fin|sail`, unless preceded by `no ` | `:fin` |
| `deck|keel|pod|section|spine|dome` | `:section` |
| `insert|plug|logo|gargoyle` | `:detail` |
| otherwise | `:unknown` |

Takes `:unknown` from 25.1% to **10.0%**; worst bundle from 69.2% to 23.1%; 13 of 19
bundles reach zero.

**Two regex details that matter.** Matching is **substring, not word-bounded** - 14
folders are CamelCase or underscore-joined (`Metis_Hull`, `GGRBridge`, `VossTorpedo`,
`BombCanon`) and word boundaries silently drop every one. **Except `ram` and `aft`, which
must be word-bounded**: `ram` as a substring hits 9 `Pyramid` folders, and `aft` hits 19
`Crafty` folders while matching nothing real, since `aft` is unused in this library.

**Rejected: an `Escort` → `:ship` fallback.** It would take `:unknown` to 1.9%, but it
asserts a fact from a directory name with no evidence, and 3 of its 137 hits are provably
wrong. Driving the number down while raising the error rate is exactly the failure this
investigation existed to prevent. Escorts get `:ship` deliberately (§5.5), not inferred.

Also rejected: `pyramid`, `mouth`, `acid`, `plasma`, `tendril`, `gland`, `KFF`, `skull`.
Each is one designer's private vocabulary worth 0.2-0.6 points. Chasing that tail is how
a rule table becomes a maintenance liability.

#### Consequences for the design

1. **The field is `:part/role-hint`, carrying `:part/role-source :inferred`.** Not
   `:part/role`. The name has to say what it is.
2. **It never feeds compatibility matching.** M3 reads mounts, which are ground truth
   established by the wizard. A ~10-20% error rate depending on bundle is fine for
   browsing and fatal for assembly.
3. **The UI renders it as a soft suggestion** - greyed and italic, overridable - the same
   treatment §5.3 gives supported-only parts. It must not look authoritative.
4. **A manual role set in the wizard overrides the hint permanently** and suppresses
   re-inference for that part.

### 5.5 Escorts: mixed, and only geometry can tell

**Measured across all 15 escort-bearing bundles (issue #3). The blanket rule this section
previously asserted is wrong.** Escorts are pre-combined whole ships in *some* bundles,
genuine kitbash in others, and **both inside the same folder** in five of them.

- **Fully pre-combined:** Human Navy, Toaster Mechanics, Ork, Anarchist Jarheads, Greater
  Good Defense, Greater Good Fish Market, Space Bugs.
- **Mixed - ships plus a real kit in one folder:** Hazard Stripe, Gloomy Jarheads,
  Interstellar Jarheads (both), Zombie Space Raider. Hazard Stripe holds 12 whole ships
  *and* a genuine two-piece `IW Barge Hull` + prow kit.
- **Genuinely kitbash, with pre-assembled convenience files alongside:** Pirate Space
  Elves, Edgy Space Elves, tiamat.

Human Navy is proven whole-ship three independent ways: cross-section extents identical
across all prow variants of a class while only length changes (Doubtless: 28.06 x 19.00
for all 8); siblings sharing 55-88% of their axial profile, against ≤36% for
known-kitbash Cruiser prows; and no `Rapier Hull` existing anywhere to attach to.

Decisive counter-case: `Pirate Elves Conium Destroyer 1` is **19 disjoint unmerged
components** whose volume multiset is exactly the union of three sibling parts (1011.21
vs 1009.78, 0.14% apart).

**This vindicates rejecting the `Escort → :ship` fallback in §5.2** - it would have been
wrong for eight bundles.

**Names cannot decide this.** `Cyanide Prow Rapier`, `Toaster Stalker Prow` and
`Gladiator Standard Prow` all say "Prow" and are whole ships; `Mercury hull and prow` says
both. Detection must be geometric, computing per part: sorted bbox extents, mesh volume,
connected-component count, and a 0.5 mm-binned axial cross-section profile - then a
variant-family test (cross-sections agreeing within `max(0.3 mm, 2%)` **and** profiles
agreeing over `≥ max(20 mm, 40% of min L)`; measured margin is 22-68 mm for ships against
≤10.5 mm for kitbash, a clean gap), plus an anchor test (a component needs a sibling of
≥2x its volume to plug into) and an assembly test via component-volume decomposition.

Absolute size does not discriminate - `Combatbarge Standard Prow` (a component) has
volume 5357 while `Gladiator Standard Prow` (a whole ship) has 1227. All comparisons must
be sibling-relative.

**Scope call: this does not belong in M1.** It needs volume and connected-component
analysis on every escort, which is far more than a catalog scan should do. M1 marks
escort-class parts `:role-hint :unknown, :role-source :inferred` and leaves them
renderable. The classifier is M2 work, where mesh analysis already happens.

### 5.3 Variant selection

```clojure
(defn source-stl [part-dir]
  (or (existing part-dir "unsupported-pitted.stl")
      (existing part-dir "unsupported.stl")))
;; supported.stl is never read - it carries print scaffolding
```

A part with only `supported.stl` (73 exist) is catalogued with `:part/variants
#{:supported}` and no renderable source. The UI shows it greyed with a reason rather than
hiding it, so the library stays a truthful inventory.

### 5.4 Incremental index

**Never hash 19 GB at startup.** SPEC §7 specifies content-addressed mesh caching, but
computing SHA-256 over the library at every boot is unacceptable.

Two keys, two purposes:

- `:part/id` - the folder path. Free, computed during the walk.
- `:part/mesh-key` - SHA-256 of the source STL. Computed **only** when a part is first
  preprocessed, which is already lazy.

`$XDG_CACHE_HOME/shipyard/index.edn` maps `path → {:mtime :size :mesh-key :tris}`. At
scan, a part whose mtime and size are unchanged reuses its cached `mesh-key`; anything
else has its entry invalidated and re-derives on next view. Re-pitting a hull changes
mtime and size, so the cache self-invalidates.

Budget: full scan of 1,661 folders, cold, **under 2 s**. It stats files and reads small
EDN; it opens no mesh.

---

## 6. Mesh pipeline

### 6.1 Parse

Binary STL: 80-byte header, `uint32` little-endian triangle count, then 50 bytes per
triangle - 3 floats face normal, 3x3 floats vertices, `uint16` attribute count.

Read via a memory-mapped `ByteBuffer` in `LITTLE_ENDIAN` order. No per-triangle object
allocation; write straight into primitive `float[]`.

**One ASCII STL exists** and must be handled - the claim that none do was wrong
(verified, issue #3): `Toaster Mechanics Fleet Bundle/Escort/Toaster Stalker Prow/
unsupported.stl`, 6.3 MB, CRLF line endings. Its binary header parses as 1,814,065,765
triangles, so a header-trusting parser allocates ~90 GB or reads garbage.

**Validate every file before parsing:** require `size == 84 + 50n` for the header's `n`.
On mismatch, fall back to the ASCII path rather than failing - a single unreadable part
in a 1,592-file library is not an acceptable outcome. A minimal ASCII reader is ~30 lines
and this is the only file needing it, but the validation guard matters more than the
reader: it is what stops a malformed or truncated file taking the process down.

### 6.2 Weld and crease-split normals

The single highest-value transform. STL shares no vertices, so raw upload costs ~3x what
it should.

**Weld on exact float bits. No fallback needed.** Measured across four Human Navy parts:
exact bit matching reaches **V/T = 0.497-0.498**, essentially the theoretical
closed-manifold ideal of 0.5, and quantized snapping to a 1e-4 mm grid produces
*identical* counts to three decimals. Exporters here emit bit-identical floats for shared
vertices, so a `HashMap` keyed on the three ints from `Float.floatToRawIntBits` is both
correct and sufficient. The quantized-snap fallback earlier drafts specified is dead
code - drop it.

**Then split by crease angle.** Welding alone gives smooth normals everywhere, which
rounds off the hard mechanical edges all over these hulls. Build vertex→face adjacency,
cluster each vertex's incident faces into smoothing groups where adjacent face normals
are within a threshold (default **35°**), and emit one output vertex per
(position, smoothing group), with an area-weighted normal.

**Measured crease-split ratios** (V/T after splitting, by threshold):

| part | T | 15° | 25° | **35°** | 45° | 60° |
|---|---:|---:|---:|---:|---:|---:|
| Cruiser Hull | 132,892 | 1.093 | 1.071 | **1.053** | 1.015 | 0.943 |
| Battleship Hull | 209,560 | 0.947 | 0.934 | **0.918** | 0.835 | 0.782 |
| Classic Ram Prow | 77,296 | 0.779 | 0.722 | **0.698** | 0.677 | 0.649 |
| Bridge | 11,064 | 1.124 | 1.084 | **1.069** | 1.028 | 0.978 |

At 35° the range is **0.70-1.07**, against raw STL's 3.0 - a **2.8-4.3x reduction**, which
confirms the estimate these numbers replace.

**35° stands as the default, and the knob barely matters.** Across 15°→60° the ratio moves
only ~15%, so this is not a parameter worth tuning per bundle. Pick it for shading
quality, not memory.

**Corrected test assertions.** Warn above **1.25**, fail at **≥ 2.5**. The earlier warn
threshold of 1.0 would have fired on two of four reference parts under normal operation -
a warning that cries wolf is worse than none.

**The weld is load-bearing for simplification, not just memory.** Measured (§6.3): a mesh
whose coincident positions differ by a single ULP tears badly under simplification -
6,684 boundary edges after decimation against 99 for the correctly welded mesh, on a model
with 896 genuine border edges. A near-miss weld does not degrade gracefully; it shreds the
LOD tiers. This makes the V/T ratio check a real guard rather than a nicety: if welding
silently fails, the visible symptom appears two stages later and looks like a simplifier
bug.

### 6.3 LOD

**`meshopt_simplifyWithAttributes`**, not plain `meshopt_simplify`, at tiers
**100% / 25% / 5%** of index count, run after `meshopt_optimizeVertexCache`.

We carry per-vertex normals, and the attribute term measurably restrains collapses that
damage shading - verified on a flat grid with varying normals, where geometric error is
zero by construction so only the attribute term can act (issue #6). **Start at attribute
weight 0.5 per normal component.** On smooth geometry both functions produce identical
output, since normals there are derived from positions; the win is precisely on hard
edges and split normals, which is what these hulls are made of.

Measured cost on 131k triangles: `optimizeVertexCache` 8.8 ms, simplify 31.1 ms. Not a
factor in the §11 budgets.

**The crease-seam question is resolved: split-then-simplify is correct.** Measured on a
117,696-triangle greebled plate (58,925 welded vertices, 78,413 after crease splitting,
28.2% of positions on a seam).

A hard floor exists but never binds. Driving `target_index_count` to 0 and raising the
error budget without limit, a crease-split mesh refuses to collapse below **3.05%** of its
original index count, while the position-welded equivalent goes to zero. The seams are
genuinely un-collapsible topology. But our tiers are 25% and 5%, both comfortably above
the floor, and both hit their target exactly. **Do not add a tier below ~8%** - that is
where this stops being theoretical.

Pipeline order, measured both ways at 25%:

| order | vertices | cost |
|---|---:|---|
| **(b) split → simplify** | 29,630 | 35 ms |
| (a) simplify → split per tier | 28,807 | 35 ms + 365 ms re-split |

Order (a) yields 3% fewer vertices for roughly 10x the time, and must re-split every tier.
**Keep (b)**, which is the order §6.2 already describes.

`simplifyWithAttributes` at weight 0.5 produced results identical to plain simplify on
seam collapse and floor behaviour, so it remains the choice for shading quality (§6.3
above) rather than for topology.

**Simplification does not compact the vertex buffer** - output indices still reference the
original array. `meshopt_optimizeVertexFetch` compacts each tier to only the vertices it
uses, and the measured saving is large: the 5% tier needs 8,030 of 78,413 vertices, about
10%.

### 6.4 Wire format - `.symesh`

**Resolved:** one self-contained file per LOD tier, each compacted with
`meshopt_optimizeVertexFetch`. Not one shared vertex buffer with N index buffers.

Sharing a vertex buffer only pays if tiers are switched at runtime, and SPEC's fleet view
is a list rendering one ship at a time - so a viewer holds exactly one tier. Compaction
then shrinks the 5% tier's vertex buffer roughly tenfold (§6.3), which is what M6
thumbnails download. Independent files also cache and evict independently over plain HTTP.

Path: `mesh/<sha256>.<tier>.symesh`, tier ∈ {0,1,2}.

Little-endian throughout, every field 4-byte aligned.

```
offset  type         field
──────────────────────────────────────────────────────────────
 0      char[8]      magic "SYMESH\0\0"
 8      uint32       version = 1
12      uint32       flags            bit0 = normals present
16      uint32       vertexCount   V
20      uint32       indexCount    I
24      float32[3]   bboxMin
36      float32[3]   bboxMax
──────────────────────────────────────────────────────────────
48      float32[3*V] positions
        float32[3*V] normals          (if flags bit0)
        uint32[I]    indices
```

Client-side decode is a handful of typed-array views - no parsing. Offsets come from
`wire.cljc`, so encoder and decoder cannot drift:

```clojure
(defn decode [^js buf]
  (let [dv (js/DataView. buf)
        V  (.getUint32 dv off-vertex-count true)
        I  (.getUint32 dv off-index-count  true)
        p  off-payload
        g  (three/BufferGeometry.)]
    (doto g
      (.setAttribute "position"
        (three/BufferAttribute. (js/Float32Array. buf p (* 3 V)) 3))
      (.setAttribute "normal"
        (three/BufferAttribute. (js/Float32Array. buf (+ p (* 12 V)) (* 3 V)) 3))
      (.setIndex
        (three/BufferAttribute. (js/Uint32Array. buf (+ p (* 24 V)) I) 1)))))
```

`bboxMin/Max` sit in the header so the camera can frame a part without scanning vertices.

Served with `Content-Encoding: gzip`. Float data compresses poorly (~10%), so this is
minor - but it is one header, and it is free.

### 6.5 Cache and concurrency

`$XDG_CACHE_HOME/shipyard/mesh/<sha256>.symesh`, written atomically (temp file + rename)
so a concurrent reader never sees a partial file. Requests for a part already being
preprocessed await the in-flight job rather than starting a second - a `ConcurrentHashMap`
of `path → CompletableFuture`.

**Thread pool sized to `availableProcessors`**, not virtual threads. Preprocessing is
CPU-bound native and array work; virtual threads help blocking I/O and would only add
scheduling overhead here. Virtual threads are correct for the Jetty request pool, which is
a separate concern.

**Cache budget and eviction.** A `.symesh` runs about **82%** of its source STL, not the
70% earlier drafts assumed - for the Cruiser hull, 5.4 MB against 6.6 MB: 139,935 welded
vertices (§6.2, 35°) at 24 B for positions plus normals, and 2.07 MB across three LOD
index tiers. Lazy generation bounds growth to what has been viewed, but the ceiling is
real: browsing the entire library would accumulate roughly **8 GB**.

So the cache is capped - **default 4 GB**, configurable, with LRU eviction by access time
on a background sweep. Every entry is regenerable from the source STL, so eviction is
always safe and never loses user data. A cold re-encode of an evicted part costs the
same as its first view.

This supersedes SPEC §11's open question, which left eviction unspecified.

### 6.6 LWJGL interop notes

Verified working practice (issue #6). These are the traps that cost real time.

- **`MemoryStack` only for small out-params** such as `result_error`. It is
  `AutoCloseable`, so `(with-open [s (MemoryStack/stackPush)] …)` is correct from
  Clojure, but the default stack is **64 KB** and it is **thread-local** - never share
  one across threads. Mesh-sized buffers go through `MemoryUtil/memAlloc*` with an
  explicit `try/finally memFree`.
- **Views do not own memory.** `MemoryUtil/memFloatBuffer` gives a zero-copy view into an
  interleaved buffer; free the backing allocation, never the view.
- **Prefer `memFloatBuffer` to NIO `.slice()` / `.asFloatBuffer()`.** `ByteBuffer.slice()`
  resets byte order to big-endian, silently corrupting float reads.
- **Interleaved attribute views need tail padding.** LWJGL's bounds check requires
  `vertex_count * (stride/4)` floats measured from the *attribute* offset, overrunning by
  one attribute - allocate 12 extra bytes at the end of the vertex buffer.
- **Strides are bytes; buffer bounds are elements.** Easy to conflate.
- **`meshopt_simplify*` returns a count and never throws** on ordinary failure. Check it.
  The result is always a multiple of 3.
- **Never enable `meshopt_SimplifyPrune` without clamping `target_error`.** Measured: at
  `target_error = 1.0` it returns **zero indices - the entire mesh deleted** - while
  0.5 and below behave normally. It fails silently by returning a count, not by throwing.
  We do not need Prune; if it is ever enabled, cap the error budget well below 1.0.
- **Leave LWJGL's bounds checks on.** They caught a real undersized-destination bug during
  the spike. Do not set `-Dorg.lwjgl.util.NoChecks=true`.
- **meshopt is stateless and reentrant.** 8 threads x 4 tiers over shared read-only source
  buffers produced byte-identical results, confirming the §6.5 pool design. Each thread
  must own its output buffers.
- **Clojure specifics.** `(set! *warn-on-reflection* true)` is essential on these hot
  interop paths. Primitive-hinted fns are limited to 4 args - use an options map for wide
  signatures. Parenthesize `(ByteOrder/nativeOrder)`; Clojure 1.12 reads the bare form as
  a method value.

---

## 7. HTTP surface

| Route | Returns |
|---|---|
| `GET /` | App shell - library panel, viewport canvas, import map |
| `GET /library` | Hiccup fragment. Params `bundle` `class` `role` `q` |
| `GET /part/:id` | Detail fragment + `HX-Trigger` to load the mesh |
| `GET /mesh/:key.symesh` | Binary (§6.4). Immutable, content-addressed |
| `GET /healthz` | Liveness |

`:id` is the percent-encoded library-relative path.

**Caching.** `/mesh/*` is content-addressed and therefore immutable:
`Cache-Control: public, max-age=31536000, immutable`. Fragments send `no-store`.

**Preprocess latency.** A cold part takes seconds. `GET /part/:id` returns the fragment
immediately with a loading state, and the mesh URL is only issued once the job completes -
so the request never blocks on the pipeline.

### 7.1 htmx contract

The canvas is `hx-preserve` and never a swap target (SPEC §6.1). All viewport
communication is `HX-Trigger`:

```clojure
{"HX-Trigger" (json/write-str
                {:shipyard/load-mesh {:url "/mesh/3f9a….symesh"
                                      :part-id "Human Navy Fleet Bundle/Cruiser/Hull"
                                      :frame true}})}
```

| Event | Payload | Meaning |
|---|---|---|
| `shipyard:load-mesh` | `url`, `part-id`, `frame` | Load and display; `frame` recentres the camera |
| `shipyard:clear` | - | Empty the scene |
| `shipyard:status` | `state`, `message` | Preprocessing progress / errors |

Event names are namespaced `shipyard:*` so they never collide with htmx's own.

### 7.2 Viewport module

`src/shipyard/viewport.cljs`, compiled by shadow-cljs - the browser island, and the only
client-side code we write.

Owns: renderer, scene, camera, `OrbitControls`, an IBL environment, a map of part-id ->
`Object3D`, and the `.symesh` decoder. Listens for the `shipyard:*` events on
`document.body`.

Materials are `MeshStandardMaterial` with a neutral studio environment. PBR from the
start because M5 paint schemes depend on it, and retrofitting lighting is worse than
building on it.

**Why ClojureScript rather than hand-written JS.** The earlier decision (SPEC §6) rejected
a ClojureScript *frontend* - re-frame owning the whole UI - in favour of server-rendered
hiccup and htmx. That still stands: htmx renders every panel. This is only the viewport
island, and three arguments carry it:

1. **It removes build machinery rather than adding it.** shadow-cljs consumes npm packages
   directly, so `(:require ["three" :as three])` replaces the copy-four-files-into-vendor
   step, the import map, and the addon path juggling that §8 previously needed. We already
   required npm for three.js, so the toolchain is not new.
2. **`wire.cljc` gives the binary format one definition.** The `.symesh` layout (§6.4) is
   otherwise implemented twice - a JVM encoder and a JS decoder - with magic bytes, field
   offsets and flags hand-mirrored across two languages. A reader-conditional namespace
   holding the offsets makes a whole class of drift bug impossible.
3. **EDN over the wire.** `HX-Trigger` payloads can carry EDN read natively by the client,
   so mount frames and transforms need no JSON marshalling layer.

**The honest cost is three.js interop.** 3D code is heavy on property mutation and matrix
math, where `(set! (.-x (.-position obj)) 1.0)` is plainly worse than `obj.position.x =
1.0`. Keep the hot render loop small and imperative; the value of CLJS here is in the
decoder, the event handling and the scene bookkeeping, not the per-frame math.

**Watch item:** `:advanced` compilation against an external JS library relies on shadow's
externs inference. It is usually clean, but if property names get munged, `^js` type hints
on three.js objects are the fix. Verify a release build early rather than at M6.

## 8. Build

**npm is the single source of truth for JavaScript dependencies.** Every JS dependency is
declared in `package.json`, pinned, and installed with `npm ci` so the lockfile is
authoritative and integrity-checked. Nothing is vendored into the repo, and nothing is
fetched from a CDN at runtime (SPEC §6.3).

```json
{ "devDependencies": { "shadow-cljs": "3.4.12" },
  "dependencies":    { "three": "0.185.1", "htmx.org": "2.0.10" } }
```

```clojure
;; shadow-cljs.edn
{:source-paths ["src"]
 :dependencies []                       ; deps.edn is the source of truth for Clojure deps
 :builds {:viewport {:target     :browser
                     :output-dir "resources/public/js"
                     :asset-path "/js"
                     :modules    {:viewport {:init-fn shipyard.viewport/init}}
                     :release    {:compiler-options {:optimizations :advanced}}}}}
```

`build.clj` steps:

1. `npm ci` - pinned versions, integrity-checked.
2. `npx shadow-cljs release viewport` - emits `resources/public/js/viewport.js` with
   three.js bundled in. No import map, no manual copying, no addon path rewriting.
3. Copy `node_modules/htmx.org/dist/htmx.min.js` into `resources/public/js/`.
4. `compile-clj`, then `uber` with all four LWJGL native classifiers.

**Why htmx is copied rather than bundled**, given it is an npm dependency like any other
and shadow-cljs could `(:require ["htmx.org"])` it. Two reasons, and the first is the one
that matters:

- **Failure isolation.** htmx drives every panel in the application; the CLJS bundle drives
  one canvas. Bundling them means a broken or slow viewport build takes the entire UI down
  with it. Kept separate, a viewport failure costs you the 3D preview and nothing else -
  browsing, loadouts and the fleet roster keep working. The dependency direction should
  match the importance: the UI must not depend on the island.
- **Initialization timing.** htmx scans the DOM for `hx-*` attributes on load. A plain
  `<script>` tag makes that ordering explicit and independent of module graph evaluation.

This is a delivery decision, not a dependency-management one. Both libraries are managed
identically by npm; only how they reach the browser differs.

Dev runs two processes: `npx shadow-cljs watch viewport` for hot-reloaded CLJS, and the
JVM server for HTML and meshes. Only the JVM process is needed to serve a release build.

`resources/public/js/` is gitignored.

## 9. CI

Forgejo Actions, matrix over `ubuntu-latest` and `windows-latest`.

The JVM is portable; **LWJGL natives are not**. But the justification is narrower than it
first appears (issue #6): the natives are prebuilt jars on Maven Central, so a Linux
runner can resolve and package the Windows classifier without trouble. **Windows CI is
needed only to *execute* tests on Windows, never to build or release.**

It still earns its place - running the pipeline against Windows natives is the only way
to catch a platform-specific failure before a user does - but if CI minutes get tight,
this is the job to cut, and cutting it does not endanger the release artifact.

```yaml
jobs:
  build:
    strategy: { matrix: { os: [ubuntu-latest, windows-latest] } }
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: actions/cache@v4
        with: { path: ~/.m2, key: ${{ matrix.os }}-m2-${{ hashFiles('deps.edn') }} }
      - run: clojure -M:test:natives-${{ matrix.os == 'windows-latest' && 'windows' || 'linux' }}
      - run: clojure -T:build uber
```

Level mapping (§10): **unit and integration run on both platforms**, since those are what
exercise natives and filesystem semantics. **E2E runs on Linux only** - it tests
application behaviour, not platform behaviour, and paying for a second headless browser
buys nothing. The library canary (§10.4) is not a CI job at all.

## 10. Testing

Three levels, distinguished by **what they are allowed to touch**, not by how big they
are. The boundary is the point: a test that needs the filesystem is not a unit test, and
pretending otherwise is what makes suites slow and flaky.

| level | may touch | budget | runs |
|---|---|---|---|
| **Unit** | nothing outside the process | < 10 s whole suite | every save |
| **Integration** | filesystem, natives, HTTP, real EDN | < 2 min | every push |
| **E2E** | a real browser against the running system | < 5 min | pull requests |

Runner is kaocha with one suite per level, so `clojure -M:test:unit` is a sub-second
feedback loop and CI can fan the levels out across jobs.

### 10.1 Unit - pure functions

No files, no sockets, no natives. Meshes are generated in memory (cube, icosphere,
greebled plate); STL parsing is tested against a `byte[]`, never a path.

| Subject | Asserts |
|---|---|
| STL parse | Triangle count and bbox on generated solids |
| Malformed STL | `size != 84 + 50n` is detected; ASCII input routes to the ASCII reader (§6.1) |
| Weld | Cube welds to 8 positions; V/T warns above 1.25, fails at 2.5 (§6.2) |
| Crease split | Cube keeps hard edges - 24 vertices, not 8 |
| LOD monotonicity | Each tier's index count strictly decreases; tier 0 is lossless |
| Wire roundtrip | encode -> decode -> geometry equal within float tolerance |
| Role inference | Table-driven over real folder names taken from the library (§5.2) |
| Mount frame | Facet -> position/axis/roll; bbox midpoint not vertex average; longest hull **edge** not diagonal |
| Assembly transform | `M = S . Tz(g) . Rx(pi) . P^-1` places a known plug on a known socket |
| Symmetry mirroring | Mirrored mount is the exact reflection; roll handedness preserved |

**`wire.cljc` is tested on both runtimes from one namespace** - JVM via kaocha, CLJS via
shadow-cljs `:target :node-test`. That cross-runtime run is the actual proof that encoder
and decoder agree on the binary layout, and it is the highest-value test in the project:
a drift bug here corrupts geometry silently rather than throwing.

### 10.2 Integration - side effects

Real filesystem, real natives, real HTTP. Each test gets a temp directory; none touch the
user's library.

| Subject | Asserts |
|---|---|
| Scanner | Fixture tree yields expected ids, roles, variants; `other/` skipped; supported-only flagged not dropped |
| Variant selection | `unsupported-pitted.stl` preferred; `supported.stl` never read (§5.3) |
| Cache lifecycle | Miss -> generate -> hit; touching an STL invalidates its `mesh-key`; LRU evicts at the cap |
| Atomic writes | A concurrent reader never observes a partial `.symesh` |
| Concurrent preprocess | Two requests for one part produce one job, not two (§6.5) |
| Sidecar | Write -> read -> equal; malformed EDN fails loudly and does **not** silently drop mounts |
| Write-through order | A failed transact still leaves the sidecar on disk (§1.2) |
| meshoptimizer | Real native calls: simplify hits target, `optimizeVertexFetch` compacts, Prune is not enabled |
| HTTP | Routes return expected fragments; `/mesh/*` sends immutable cache headers; `HX-Trigger` payloads parse |

**This level is what the Windows runner is for.** Beyond the LWJGL natives (§9), Windows
differs on the things this level exercises: path separators, file locking, and
`Files.move` atomicity - and the cache depends on temp-file-plus-rename being atomic.

### 10.3 E2E - headless browser

**etaoin** driving headless Chrome against a real server started on an ephemeral port,
backed by the fixture library. Clojure end to end, no separate JS test stack.

| Flow | Asserts |
|---|---|
| Browse and filter | Library panel lists fixture parts; filters narrow correctly |
| Load a part | Selecting a part fires `shipyard:load-mesh`; the viewport reports it loaded |
| Canvas survives swaps | An htmx swap elsewhere leaves the WebGL context alive (`hx-preserve`, §6.1) |
| Mount wizard (M2) | Clicking a face returns a highlighted facet and a plausible frame |
| Assembly (M3) | Choosing a prow places it at the socket transform |
| Paint (M5) | Scrubbing a colour updates the material live; release persists it |
| Degraded mode | With the viewport bundle blocked, browsing and loadouts still work (§8) |

**Asserting on WebGL is the hard part, and pixels are the wrong answer.** Screenshot
diffing a 3D scene is brittle - driver, antialiasing and timing all move it. Instead the
viewport exposes a **test-only introspection hook**, `window.__shipyard.stats()`,
returning scene facts: loaded part ids, vertex and draw counts, camera target, material
colours. Assertions read that. It is compiled out of release builds via a `goog-define`,
so it cannot ship.

One screenshot test remains, and it only asks the crudest question: **is the canvas
non-blank?** Sample pixels and assert they are not uniform. That catches "nothing rendered
at all", which the stats hook cannot - the hook would happily report a loaded mesh that
never reached the screen.

**Headless Chrome needs software rendering for WebGL in CI** (SwiftShader). Verify this in
the first E2E test written, not at M6 - a CI box with no GPU will otherwise fail in a way
that looks like an application bug.

### 10.4 Fixtures, and the library canary

Fixtures are small generated STLs committed to the repo, in a directory tree mirroring the
real structure including its edge cases: an ASCII STL, a supported-only folder, an
`other/` directory, a `weapons/` subdirectory, and a pitted variant. **CI never depends on
the 19 GB library.**

But fixtures only contain problems we already know about. The ASCII STL (§6.1) was found
by scanning the real collection, and no fixture suite would ever have produced it. So
there is a fourth thing, deliberately not a test level:

**A library canary** - `clojure -M:canary` - runs the scanner and preprocessor across the
whole real library and reports anomalies: files failing the `84 + 50n` check, welds
breaching the V/T ceiling, empty or non-manifold meshes, parts with no renderable variant.
Run on demand and after acquiring new bundles. It is a data-quality probe, not a pass/fail
gate, and it belongs to no CI job.

## 11. Performance budgets

Targets M1 must hold. Measured on the Human Navy Cruiser (SPEC §4).

| Operation | Budget |
|---|---|
| Cold scan, 1,661 part folders | < 2 s |
| Warm start from `index.edn` | < 500 ms |
| Preprocess Cruiser hull (133k tris) | < 2 s |
| Preprocess heaviest part (1.2M tris) | < 15 s |
| Serve cached `.symesh` | < 50 ms |
| Viewport, one ship (270k-600k tris) | 60 fps |
| Peak heap, preprocessing | < 2 GB |

If the cold preprocess budget fails, the lazy-cache design is what protects the user
experience - it is paid once per part, ever.

## 12. Open questions

- **Escort classification** (SPEC §11) blocks accurate role inference. Verify by
  inspecting geometry - a pre-combined escort should show one connected component with a
  hull-like bbox - before M2 depends on it.
- **Crease angle 35°** is a starting guess. Tune against real hulls; it may need to be
  per-bundle if designers differ in how they export.
- **`:unknown` role frequency** is unmeasured. If it is most of the library, the role
  table needs work - or roles should come from the mount wizard instead of filenames.
- **Sidecar write conflicts** if the library is on shared storage. Single-user assumption
  for now; a lock file is the cheap fix if it ever matters.
- **Single-ship bundles break the role model conceptually** (issue #5). Role presupposes
  alternatives competing for a slot, but the four numbered bundles hold 56 parts that are
  *sections of one model, all of which get printed*. `Bloody Iron Forward hull` and
  `Rear Hull` are two halves, not two choices. Filtering `:hull` mixes 160 interchangeable
  hulls with 11 non-interchangeable fragments. This needs a `:bundle/kind :single-ship`
  flag or a part-level `:assembly` grouping - neither derivable from a folder name, so it
  is a missing concept rather than a rule-table bug. Decide before M3.
- **`ordinance/` contains 11 flight stands** (`Bomber Base`, `Fighter Base`) which are not
  ordnance. Minor, but they will show up in the wrong filter.
- **Cache cap of 4 GB** (§6.5) is a guess pending real usage. If normal browsing evicts
  parts that get re-viewed minutes later, raise it; the sweep should log evictions so
  that is visible rather than inferred.
