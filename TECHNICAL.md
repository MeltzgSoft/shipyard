# Shipyard — Technical Specification

Implementation-level design. Scope: **M1** (library scan, catalog, mesh pipeline,
single-part viewer) in full detail, plus the system-wide foundations M1 forces us to
commit to — project layout, dependencies, storage, and the HTTP contract — which every
later milestone inherits.

M2–M6 are deliberately not specced at this depth. M1 will teach us things about the mesh
pipeline that would invalidate the guesses.

Companion to [SPEC.md](SPEC.md), which covers product scope and architecture rationale.

---

## 1. Resolved decisions

Three questions SPEC.md left open, now settled.

### 1.1 No glTF — a purpose-built wire format

SPEC §7 said encode to `.glb`. Dropped. We control both ends of the wire, so glTF's
interop value is close to zero here, while writing a GLB encoder on the JVM means a JSON
chunk, a BIN chunk, accessor/bufferView bookkeeping, and no mainstream Clojure library to
lean on.

Instead: a flat binary format (§6) that decodes straight into a three.js
`BufferGeometry` with no parsing beyond typed-array views over the received
`ArrayBuffer`. Roughly 40 lines to write, 15 to read.

The encoder lives behind `shipyard.mesh.wire` alone. If interop ever matters, swapping in
GLB touches one namespace.

### 1.2 Datascript for querying — it does not replace the sidecars

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
     loadouts.edn  fleets.edn  schemes.edn ┘       fleets, schemes — all queryable

   $XDG_CACHE_HOME/shipyard/
     index.edn        scan cache, mtime+size keyed
     mesh/<sha>.symesh  encoded meshes
```

**Read path.** At startup, scan the library, read every `shipyard.edn`, read the user
data files, transact the lot into a fresh Datascript DB. 1,661 small EDN files is a fast
read; no mesh is touched (§5.3).

**What each layer may hold — and what never moves.**

| Layer | Holds | Never holds |
|---|---|---|
| Datascript | Metadata only: ids, names, roles, variants, a triangle count, a content hash, mount frames | Any geometry. Not one vertex |
| `$XDG_CACHE_HOME/…/mesh/` | Derived `.symesh` encodings, regenerable from source at any time | Copies of STLs |
| The library | The STLs, exactly where they are | — |

**Source STLs are never copied, moved, or ingested.** They are opened lazily, once, when
a part is first viewed, and read again only if their mtime or size changes. The full
library at rest is untouched by Shipyard; deleting the entire cache costs nothing but
recomputation.

The naming invites a misreading worth heading off: `:part/mesh-key` is the SHA-256 *of*
the source STL, used to name the derived file `mesh/<sha>.symesh`. The STL itself is not
stored under that hash — the hash is an identity for the encoding produced from it.

**Write path is write-through, file first.**

```clojure
(defn save-mount! [part-id mount]
  (sidecar/update! part-id #(update % :mounts conj mount))  ; 1. durable write
  (d/transact! conn [(mount->tx part-id mount)]))           ; 2. index update
```

File first matters: if the transact throws, the data is already safe on disk and the next
restart picks it up. The reverse order can lose a write.

**Why Datascript earns its place** even though M1's queries are simple: compatibility
filtering in M3 is a genuine join — every part whose role satisfies some socket's
`accepts`, within a class, excluding those already slotted. That's a datalog one-liner
and an awkward nest of `filter` over maps. Establishing it in M1 avoids a migration.

**Why not Datalevin**, which would be durable and remove the ingest step: it would make a
database the source of truth for data that wants to live beside the STLs — greppable,
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
self-describing — a copied part folder carries its own mount data.

---

## 2. Project layout

```
shipyard/
├── deps.edn
├── build.clj                       tools.build: vendor fetch, uberjar
├── package.json                    pinned three.js + htmx, fetched at build
├── SPEC.md  TECHNICAL.md
├── src/shipyard/
│   ├── main.clj                    entry point, system lifecycle
│   ├── config.clj                  XDG paths, library root, tunables
│   ├── library/
│   │   ├── scan.clj                part-folder discovery
│   │   └── index.clj               mtime+size scan cache
│   ├── catalog/
│   │   ├── db.clj                  datascript conn, schema, queries
│   │   └── sidecar.clj             shipyard.edn read/write
│   ├── mesh/
│   │   ├── stl.clj                 binary STL parse
│   │   ├── weld.clj                vertex dedup + crease-split normals
│   │   ├── lod.clj                 meshoptimizer via LWJGL
│   │   ├── wire.clj                .symesh encode
│   │   └── cache.clj               lazy preprocess + disk cache
│   └── http/
│       ├── routes.clj              reitit
│       ├── views.clj               hiccup
│       └── htmx.clj                HX-Trigger helpers
├── resources/public/
│   ├── app.css
│   ├── viewport.js                 the only hand-written JS
│   └── vendor/                     GITIGNORED — populated at build
└── test/shipyard/
    ├── fixtures/                   small generated STLs, committed
    └── mesh/…
```

## 3. Dependencies

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps
 {org.clojure/clojure            {:mvn/version "1.12.0"}
  metosin/reitit-ring            {:mvn/version "0.7.2"}
  ring/ring-jetty-adapter        {:mvn/version "1.12.2"}
  hiccup/hiccup                  {:mvn/version "2.0.0-RC3"}
  datascript/datascript          {:mvn/version "1.7.3"}
  integrant/integrant            {:mvn/version "0.13.1"}
  org.clojure/tools.logging      {:mvn/version "1.3.0"}
  org.lwjgl/lwjgl                {:mvn/version "3.3.6"}
  org.lwjgl/lwjgl-meshoptimizer  {:mvn/version "3.3.6"}}
 :aliases
 {:natives-linux   {:extra-deps {org.lwjgl/lwjgl {:mvn/version "3.3.6" :classifier "natives-linux"}
                                 org.lwjgl/lwjgl-meshoptimizer {:mvn/version "3.3.6" :classifier "natives-linux"}}}
  :natives-windows {:extra-deps {org.lwjgl/lwjgl {:mvn/version "3.3.6" :classifier "natives-windows"}
                                 org.lwjgl/lwjgl-meshoptimizer {:mvn/version "3.3.6" :classifier "natives-windows"}}}
  :natives-macos   {…}  ; natives-macos and natives-macos-arm64
  :build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.5"}} :ns-default build}
  :test  {:extra-paths ["test"] :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}}}}}
```

`lwjgl-meshoptimizer` 3.3.6 verified present on Maven Central. The uberjar bundles **all
four** native classifiers so one artifact runs everywhere; LWJGL selects at runtime. Cost
is a few MB.

No CSG dependency — face picking (SPEC §5) removed the need, which is what makes the pure
JVM stack viable.

---

## 4. Datascript schema

```clojure
(def schema
  {:part/id          {:db/unique :db.unique/identity}   ; relative folder path
   :part/bundle      {:db/index true}
   :part/class       {:db/index true}                   ; :cruiser, :escort, nil
   :part/role        {:db/index true}                   ; :hull :prow :bridge :antenna :weapon :ordinance
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

`:part/id` is the library-relative folder path — stable, human-readable, debuggable in a
URL. Distinct from `:part/mesh-key`, the content hash used for mesh caching (§5.4).

The M1 query surface is small, but the shape it establishes is what M3 needs:

```clojure
;; M1: browse
(d/q '[:find ?id ?name :in $ ?bundle ?class
       :where [?e :part/bundle ?bundle] [?e :part/class ?class]
              [?e :part/id ?id] [?e :part/name ?name]]
     @conn "Human Navy Fleet Bundle" :cruiser)

;; M3: what can go in this socket — the join that justifies datascript
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

- **bundle** — first segment.
- **class** — second segment if present and not `weapons`/the part folder itself. Absent
  in the four single-ship bundles.
- **weapons** — presence of a `weapons` segment.
- **name** — the part folder's own name.

### 5.2 Role inference

Ordered rules, first match wins. Case-insensitive on the folder name.

| Rule | Role |
|---|---|
| A `weapons` path segment | `:weapon` |
| Class segment is `ordinance` | `:ordinance` |
| Name matches `hull` | `:hull` |
| Name matches `prow` | `:prow` |
| Name matches `bridge` | `:bridge` |
| Name matches `antenna|sensor` | `:antenna` |
| Name matches `wing|fin|sail` | `:fin` |
| otherwise | `:unknown` |

`:unknown` is expected and fine — the mount wizard is what actually establishes
compatibility, and role is only a browsing/filtering convenience. The scanner must never
fail on an unrecognised name.

**Escorts are a known open question** (SPEC §11): they look like pre-combined whole ships,
which would make them `:ship` rather than a kitbash part. M1 tags them `:unknown` and
leaves it; verify before M2 relies on it.

### 5.3 Variant selection

```clojure
(defn source-stl [part-dir]
  (or (existing part-dir "unsupported-pitted.stl")
      (existing part-dir "unsupported.stl")))
;; supported.stl is never read — it carries print scaffolding
```

A part with only `supported.stl` (73 exist) is catalogued with `:part/variants
#{:supported}` and no renderable source. The UI shows it greyed with a reason rather than
hiding it, so the library stays a truthful inventory.

### 5.4 Incremental index

**Never hash 19 GB at startup.** SPEC §7 specifies content-addressed mesh caching, but
computing SHA-256 over the library at every boot is unacceptable.

Two keys, two purposes:

- `:part/id` — the folder path. Free, computed during the walk.
- `:part/mesh-key` — SHA-256 of the source STL. Computed **only** when a part is first
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
triangle — 3 floats face normal, 3×3 floats vertices, `uint16` attribute count.

Read via a memory-mapped `ByteBuffer` in `LITTLE_ENDIAN` order. No per-triangle object
allocation; write straight into primitive `float[]`.

ASCII STL is not supported. Detect (`solid ` prefix *and* size ≠ 84 + 50n) and fail with
a clear message rather than producing garbage. No ASCII files exist in the library.

### 6.2 Weld and crease-split normals

The single highest-value transform. STL shares no vertices, so raw upload costs ~3× what
it should.

**Weld on exact float bits first.** Exporters emit bit-identical floats for shared
vertices, so exact matching is correct and fast — a `HashMap` keyed on the three ints
from `Float.floatToRawIntBits`. Quantized snapping is the fallback for meshes that have
been through a transform, engaged only when the exact pass fails the ratio check below.

**Then split by crease angle.** Welding alone gives smooth normals everywhere, which
rounds off the hard mechanical edges all over these hulls. Build vertex→face adjacency,
cluster each vertex's incident faces into smoothing groups where adjacent face normals
are within a threshold (default **35°**), and emit one output vertex per
(position, smoothing group), with an area-weighted normal.

**Ratio check as a correctness test.** A closed manifold with no creases welds to
`V ≈ T/2`; unwelded STL is `V = 3T`. Hard-edged models land in between. Assert
`V/T < 1.5`, log a warning above `1.0`, and fail the fixture test outright at `≥ 2.5`,
which means welding silently did nothing.

Honest expectation: SPEC §7's "roughly V ≈ T/2" is the smooth-manifold ideal. With crease
splitting on mechanical hulls, expect `0.6–1.2`. Still a 2.5–5× reduction over raw.

### 6.3 LOD

`MeshOptimizer/meshopt_simplify` at tiers **100% / 25% / 5%** of index count, target
error 0.01, run after `meshopt_optimizeVertexCache`.

The key property: simplified index buffers **reference the same vertex buffer**. All
tiers share one set of positions and normals, so a multi-LOD mesh costs one vertex buffer
plus a few small index buffers — which is exactly what §6.4 encodes.

M1 renders tier 0 only. Tiers exist because generating them is nearly free once
meshoptimizer is in the path, and M6 thumbnails plus any future simultaneous-fleet view
need them (SPEC §7).

### 6.4 Wire format — `.symesh`

Little-endian throughout, every field 4-byte aligned.

```
offset  type         field
──────────────────────────────────────────────────────────────
 0      char[8]      magic "SYMESH\0\0"
 8      uint32       version = 1
12      uint32       flags            bit0 = normals present
16      uint32       vertexCount   V
20      uint32       lodCount      L
24      float32[3]   bboxMin
36      float32[3]   bboxMax
48      uint32[2*L]  per-LOD (byteOffset, indexCount), offsets from file start
──────────────────────────────────────────────────────────────
        float32[3*V] positions
        float32[3*V] normals          (if flags bit0)
        uint32[...]  index buffers, concatenated in LOD order
```

Client-side decode is a handful of typed-array views — no parsing:

```js
const dv = new DataView(buf), V = dv.getUint32(16, true), L = dv.getUint32(20, true);
let p = 48 + 8 * L;
const pos = new Float32Array(buf, p, 3 * V);            p += 12 * V;
const nrm = new Float32Array(buf, p, 3 * V);
const g = new THREE.BufferGeometry();
g.setAttribute("position", new THREE.BufferAttribute(pos, 3));
g.setAttribute("normal",   new THREE.BufferAttribute(nrm, 3));
g.setIndex(new THREE.BufferAttribute(
  new Uint32Array(buf, dv.getUint32(48, true), dv.getUint32(52, true)), 1));
```

`bboxMin/Max` are in the header so the camera can frame a part without scanning vertices.

Served with `Content-Encoding: gzip`. Float data compresses poorly (~10%), so this is
minor — but it is one header, and it is free.

### 6.5 Cache and concurrency

`$XDG_CACHE_HOME/shipyard/mesh/<sha256>.symesh`, written atomically (temp file + rename)
so a concurrent reader never sees a partial file. Requests for a part already being
preprocessed await the in-flight job rather than starting a second — a `ConcurrentHashMap`
of `path → CompletableFuture`.

**Thread pool sized to `availableProcessors`**, not virtual threads. Preprocessing is
CPU-bound native and array work; virtual threads help blocking I/O and would only add
scheduling overhead here. Virtual threads are correct for the Jetty request pool, which is
a separate concern.

**Cache budget and eviction.** A `.symesh` runs about 70% of its source STL — for the
Cruiser hull, ~4.6 MB against 6.6 MB (106k welded vertices at 24 B, plus three LOD index
tiers at ~2.1 MB). Lazy generation bounds growth to what has actually been viewed, but
the ceiling is real: browsing the entire library would accumulate roughly 7 GB.

So the cache is capped — **default 4 GB**, configurable, with LRU eviction by access time
on a background sweep. Every entry is regenerable from the source STL, so eviction is
always safe and never loses user data. A cold re-encode of an evicted part costs the
same as its first view.

This supersedes SPEC §11's open question, which left eviction unspecified.

---

## 7. HTTP surface

| Route | Returns |
|---|---|
| `GET /` | App shell — library panel, viewport canvas, import map |
| `GET /library` | Hiccup fragment. Params `bundle` `class` `role` `q` |
| `GET /part/:id` | Detail fragment + `HX-Trigger` to load the mesh |
| `GET /mesh/:key.symesh` | Binary (§6.4). Immutable, content-addressed |
| `GET /healthz` | Liveness |

`:id` is the percent-encoded library-relative path.

**Caching.** `/mesh/*` is content-addressed and therefore immutable:
`Cache-Control: public, max-age=31536000, immutable`. Fragments send `no-store`.

**Preprocess latency.** A cold part takes seconds. `GET /part/:id` returns the fragment
immediately with a loading state, and the mesh URL is only issued once the job completes —
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
| `shipyard:clear` | — | Empty the scene |
| `shipyard:status` | `state`, `message` | Preprocessing progress / errors |

Event names are namespaced `shipyard:*` so they never collide with htmx's own.

### 7.2 Viewport module

`resources/public/viewport.js`, an ES module — the only hand-written JS in M1.

Owns: renderer, scene, camera, `OrbitControls`, an IBL environment, a `Map` of part-id →
`Object3D`, and the `.symesh` decoder. Listens for the events above on `document.body`.

Materials are `MeshStandardMaterial` with a neutral studio environment. PBR from the
start because M5 paint schemes depend on it, and retrofitting lighting is worse than
building on it.

---

## 8. Build

Frontend dependencies are **fetched at build time and packaged into the jar**; no vendored
copies in the repo (SPEC §6.3).

```json
{ "dependencies": { "three": "0.169.0", "htmx.org": "2.0.3" } }
```

`build.clj` steps:

1. `npm ci` — pinned versions, integrity-checked.
2. Copy into `resources/public/vendor/`:
   - `three/build/three.module.js`
   - `three/examples/jsm/controls/OrbitControls.js`
   - `three/examples/jsm/environments/RoomEnvironment.js`
   - `htmx.org/dist/htmx.min.js`
3. `compile-clj`, then `uber` with all four LWJGL native classifiers.

three.js addons import bare `"three"`, so the shell needs an import map:

```html
<script type="importmap">
{"imports":{"three":"/vendor/three.module.js","three/addons/":"/vendor/addons/"}}
</script>
```

No bundler. three.js ships as an ES module and the import map covers resolution.

`resources/public/vendor/` is gitignored — already committed in `.gitignore`.

## 9. CI

Forgejo Actions, matrix over `ubuntu-latest` and `windows-latest`.

The JVM is portable; **LWJGL natives are not**, and that layer is the one most likely to
break silently on one platform. The Windows runner exists to exercise the mesh pipeline
against Windows natives, not merely to compile.

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

**Pipeline smoke test** on both platforms: parse a fixture STL, weld it, generate LOD
tiers, encode `.symesh`, and assert triangle/vertex counts and the LOD tier sizes against
recorded values. Fixtures are small generated solids committed to the repo — CI never
depends on the 19 GB library.

## 10. Testing

| Test | Asserts |
|---|---|
| STL parse | Triangle count and bbox on a generated cube and icosphere |
| ASCII rejection | Clear error, no garbage geometry |
| Weld ratio | Cube welds to 8–24 verts; sphere to `V/T < 1.5`; fail at `≥ 2.5` |
| Crease split | Cube keeps hard edges — 24 verts, not 8 |
| LOD monotonicity | Each tier's index count strictly decreases; tier 0 is lossless |
| Wire roundtrip | Encode → decode → geometry equals input within float tolerance |
| Scan | Fixture tree yields expected ids, roles, variants; `other/` skipped |
| Role inference | Table-driven over real names taken from the library |
| Sidecar roundtrip | Write → read → equal; malformed EDN fails loudly, doesn't drop mounts |
| Cache invalidation | Touching an STL invalidates its `mesh-key` |

## 11. Performance budgets

Targets M1 must hold. Measured on the Human Navy Cruiser (SPEC §4).

| Operation | Budget |
|---|---|
| Cold scan, 1,661 part folders | < 2 s |
| Warm start from `index.edn` | < 500 ms |
| Preprocess Cruiser hull (133k tris) | < 2 s |
| Preprocess heaviest part (1.2M tris) | < 15 s |
| Serve cached `.symesh` | < 50 ms |
| Viewport, one ship (270k–600k tris) | 60 fps |
| Peak heap, preprocessing | < 2 GB |

If the cold preprocess budget fails, the lazy-cache design is what protects the user
experience — it is paid once per part, ever.

## 12. Open questions

- **Escort classification** (SPEC §11) blocks accurate role inference. Verify by
  inspecting geometry — a pre-combined escort should show one connected component with a
  hull-like bbox — before M2 depends on it.
- **Crease angle 35°** is a starting guess. Tune against real hulls; it may need to be
  per-bundle if designers differ in how they export.
- **`:unknown` role frequency** is unmeasured. If it is most of the library, the role
  table needs work — or roles should come from the mount wizard instead of filenames.
- **Sidecar write conflicts** if the library is on shared storage. Single-user assumption
  for now; a lock file is the cheap fix if it ever matters.
- **Cache cap of 4 GB** (§6.5) is a guess pending real usage. If normal browsing evicts
  parts that get re-viewed minutes later, raise it; the sweep should log evictions so
  that is visible rather than inferred.
