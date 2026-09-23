# Shipyard

A desktop application for previewing Battlefleet Gothic miniatures assembled from
existing STL part libraries - choose a hull, prow, bridge and weapon loadout, see the
result rendered as a complete ship, orbit and zoom it, design a paint scheme for it,
and keep the result as a named loadout in a fleet roster.

This document defines the intended product behavior and design.

---

## 1. Problem

The BFG model collection is a kitbash system. A Human Navy Cruiser is not one file - it
is a hull, one of twelve interchangeable prows, a bridge, two antennae, and a set of
weapon modules that repeat across several mount points. The combinatorics are large and
there is no way to see a combination before committing to it.

Today the only way to evaluate a loadout is to print it. A cruiser is hours of resin and
a one-way decision. Worse, the parts are not visually comparable in isolation: prows are
distinguished by silhouette details that only read once mounted on the hull.

Shipyard closes that loop. Pick parts, see the assembled ship, decide before printing.

## 2. Goals

- Browse the STL library by bundle, hull class, and part role.
- Assemble a ship from compatible parts and render it as one model.
- Orbit, pan and zoom with detail preserved on high-density meshes.
- Design a paint scheme against the assembled model.
- Save named loadouts and organise them into a fleet roster.
- Define how parts mate by picking faces in the viewport, and persist those mounts as
  durable data that accumulates across sessions.
- Ship a user manual that describes what Shipyard can actually do today, kept current
  with the software rather than written once at the end (§12).

## 3. Non-goals

Explicitly out of scope. These are deliberate exclusions, not deferred work.

- **No merged STL / 3MF export.** Shipyard does not produce printable files. Parts are
  printed separately and magnet-assembled physically; there is no digital kitbash output.
- **No print plate layout, support generation, or slicer integration.**
- **No automatic magnet pitting.** Deferred, not rejected - see §5.2. Because mounts are
  now defined by face picking rather than derived from pits, alignment no longer depends
  on it, so it buys nothing on the critical path. Pitting continues via `/stl-modify`.
- **No game rules.** No points values, weapon stats, fleet legality, or army lists.
- **No mesh repair as a feature.** Meshes are read as they are. Repair only happens
  incidentally where a boolean operation demands it.

## 4. Source material

Measured against one real collection: ~15 bundles, 19 GB, 3,160 STLs. Every figure in this
document and in TECHNICAL.md comes from it. Shipyard itself has no default library - the
user says where theirs is (TECHNICAL.md §7.3).

**One folder per part, holding all of that part's variants:**

```
<Bundle>/[<Class>/][weapons/]<Part Name>/
    unsupported.stl              the geometry Shipyard reads
    unsupported-pitted.stl       where magnet pits have been cut (4 across the library)
    supported.stl                print-prepared, with support scaffolding - ignored
```

- **The folder name is the part identity.** No parsing a part name out of a filename.
- **Variant selection is a filename lookup**, not path archaeology: read
  `unsupported.stl` only. Record but never display `unsupported-pitted.stl` or
  `supported.stl`.
- `<Class>` is absent in the four single-ship bundles, which are one ship each.
- `weapons/` is a role hint the scanner can trust.
- 1,661 part folders. 173 of them hold a single file, because those parts ship in only
  one form upstream - expected, not an error.

`other/` directories sit alongside and are not part folders: Lychee `.lys` project files
(1,489 across the collection, self-contained binaries embedding their own geometry) plus
occasional `README.txt` files carrying assembly notes in prose. Neither is consumed by
Shipyard v1, but the `.lys` names enumerate combinations the designers intended and may
be useful later for seeding the catalog.

Measured, `unsupported*.stl` only: 1,592 files, ~118M triangles, mean 74k per part.

| Subject | Triangles |
|---|---|
| Human Navy Cruiser, assembled | ~270k |
| Human Navy Battleship, assembled | ~600k |
| Heaviest single ship (Unbreakable Speculation, 3 hull sections) | ~2.7M |
| Ork Battle Krooza hull, one part | 679k |

Two structurally different kinds of model live in the same collection:

- **Kitbash classes** - Cruiser, Grand Cruiser, Battleship. Separate hull, prow, bridge,
  antenna and weapon files. These are what Shipyard is for.
- **Escorts are mixed.** Some bundles contain pre-combined whole ships, others contain
  genuine kitbash parts, and some contain both. Names do not distinguish them reliably;
  classification requires geometry and sibling-relative evidence.

`other/` directories contain Lychee `.lys` project files (1,489 across the collection),
named per configuration, plus occasional `README.txt` files carrying assembly notes in
prose. Shipyard does not consume either format.

### 4.1 Parts are not in a shared coordinate frame

The critical constraint. Parts are laid out for printing, not assembly. Human Navy
Cruiser bounding box minima:

```
Hull/                   (-19.06, -18.87,  5.00)   centred, Z is the long axis
Bridge/                 ( -7.58,  -9.63,  5.00)   shares the hull's frame
Classic Ram Prow/       (400.00,   0.00,  0.00)   parked at X=400 on the plate
Cyanide Nova Prow/      ( 90.00,   0.00,  0.00)   X=90
weapons/Weapon Battery/ (230.00,  25.00,  0.00)   X=230, Y=25
```

Every part except the hull and bridge sits at its own plate slot, rotated flat for
printing. Loading parts and rendering them as-is produces a scattered plate, not a ship.
Placement metadata is therefore unavoidable, and pre-aligning 1,661 parts by hand is not
a real option.

---

## 5. The mount model

The heart of the design. A mount is defined by **picking a face** - the flat surface where
two parts meet. On a weapon module you pick its backside; on a hull you pick each seat
where a weapon goes. That single interaction supplies all six degrees of freedom.

### 5.1 A picked face is a complete frame

| Derived from | Supplies |
|---|---|
| Facet bounding-box midpoint | **Position** |
| Facet normal | **Axis** (the frame's +Z) |
| Canonical part up projected onto the face | **Twist reference** (the frame's +X) |

A **socket** is such a frame on a hull; a **plug** is the corresponding frame on a module.

Two details matter, both learned the hard way in the existing `/stl-modify` workflow and
worth carrying over rather than rediscovering:

- **Use the bounding-box midpoint, not the vertex average.** On facets with four or more
  triangles the vertex average is skewed, because interior vertices get counted multiple
  times and pull the centre off.
- **Use the longest hull *edge*, not the longest diagonal.** The diagonal connects
  non-adjacent corners and sits at a steeper angle than the true long axis, which
  produces a visibly wrong roll.

### 5.2 Pitting is decoupled from alignment

This is the significant consequence: **magnet pits are no longer needed for alignment.**
An unpitted part can be mounted the moment its face is picked. Pitting reduces to a
purely physical concern - where to drill for magnets - and its location is *derived from*
the mount frame rather than the other way round, since the pit centre is exactly the
facet's bounding-box midpoint that the frame already records.

That removes the dependency on the 4 currently-pitted files, unblocks the other ~1,588
parts immediately, and means alignment work no longer has to wait on boolean geometry.
Automatic pitting is consequently out of scope for now (§3).

Where both pitted and unpitted variants of a hull exist, the boolean difference between
them still isolates the cut volumes and can *seed* socket positions automatically. That
remains a useful accelerator for those four models, but it is now an optimisation rather
than the mechanism.

### 5.3 Assembly transform

Given parent and child mount frames, both with +Z pointing outward from their mating
surfaces, assembly keeps the child's saved source-to-canonical pose `Qc`. It adds only
the global-Y turn `Ry(θ)` needed to oppose the two mount normals, then translates the
child mount onto the parent mount:

```
M = T(parent-pos + g · parent-axis - Ry(θ) · Qc · child-pos) · Ry(θ) · Qc
```

`θ` is chosen in world space, so the configured top remains up on either side of a hull.
For vertical mount faces, yaw is unconstrained by the normals; the child instead inherits
the assembled parent's canonical forward heading. A pair whose normals cannot oppose
under a global-Y turn is rejected rather than intersected. `g` is an optional gap along
the parent outward axis, default 0 - the virtual model mates flush, but a small positive
value can represent the physical standoff introduced by magnets if that turns out to
matter visually.

Frames are persisted in source mesh coordinates. The root matrix is its
source-to-canonical orientation; every child matrix already includes its saved pose and
the alignment turn, so the viewport must not apply another part-orientation transform.
Matrix payloads use 16 column-major numbers (TECHNICAL.md §13).

Socket capacity divides the selected face into equal-width **vertical** sections or
equal-height **horizontal** sections in its authored frame. The user chooses the
direction and sees boundaries and section centers on the model. Each section supplies
one independently assignable socket; capacity alone cannot supply their positions.

### 5.4 The face-picking wizard

The primary authoring interaction. Two flows, differing only in how many faces are picked.

**For a module** (weapon, prow, bridge, antenna) - pick **one** face, its mating back
surface. Shipyard shows the complete frame as arrows: outward normal `+Z`, roll reference
`+X`, and derived up `+Y`; the user confirms or adjusts, names it, saves. One plug per
module.

**For a hull** - pick **N** faces, one per seat: each weapon shelf, the prow cap, the
bridge deck. Each gets an id and an acceptance profile. Most profiles contain exactly
one role and are presented as radios. Hulls additionally offer one named shared profile,
`#{:turret :antenna}`; weapon sockets offer only `#{:turret}`. Multiple sockets per hull
remain available.

**Turret pits are a socket like any other**, and the wizard must let a picked face be
classified as one - `:mount/accepts #{:turret}`. They are worth calling out for two
reasons. First, a turret does not mount on the hull at all in the usual sense: it drops
into a pit, and those pits appear both on weapon batteries and on hulls of cruiser class
and larger, so **a socket can live on a part that is itself a component**. That is the
first case of part-on-part mounting, and the wizard cannot assume a socket implies a
hull. Second, **no name tells you which parts have them** - it is geometry, and the
catalog's `:part/accepts-turrets?` is a coarse hint (180 parts, 10.8%) meant only to give
this step a shortlist to work through.

Turret pits are also the case where picking many similar faces in a row is normal: a
cruiser dorsal spine can carry several. The wizard should make repeating a classification
cheap rather than demanding the full flow per pit.

**Symmetry mirroring halves the work.** These ships are bilaterally symmetric - the Human
Navy Cruiser hull spans X ∈ [-19.06, 19.06] about a centreline at zero. Picking `port-1`
offers to generate `starboard-1` by mirroring the frame across the hull's symmetry plane.
Since mount authoring is the project's main cost centre (§11), this is one of the
highest-leverage features in the application.

**Vocabulary.** Weapon mounts are often protrusions with two candidate faces, and the
existing workflow already names them: the **seat** is the outward-facing end cap at the
tip, the **back** is the broad face flush with the hull. The wizard uses the same terms.

#### Interaction mechanics

Face picking fits the server-rendered model unusually well, because the geometry
reasoning stays on the JVM:

1. The client raycasts the click against the mesh and gets a **triangle index**.
2. It POSTs that index. The server groups coplanar adjacent triangles into a **facet**,
   computes the frame, and returns the facet's triangle list plus the derived frame in an
   `HX-Trigger` event.
3. The viewport draws the highlight overlay and all three orientation-frame indicators.

One round trip per click, which is entirely acceptable for a deliberate authoring action,
and it means no facet-grouping or convex-hull code has to exist in JavaScript.

#### Facet indices are never persisted

Coplanar-facet grouping is order-dependent and not stable between runs - the existing
Python tooling documents this explicitly and warns against hardcoding a facet index from
one script into another. Shipyard therefore stores only the **derived frame**, which is
stable geometry independent of traversal order. A facet index is a transient handle valid
within a single request, never a stored reference.

### 5.5 Mount records are the durable artefact

The existing `/stl-modify` workflow discards its working data - its instructions delete
the scripts after each run, so the only record of a mount is baked into an output STL.
Recording mounts as first-class data makes them serve three purposes at once: alignment
frames for the virtual preview, magnet drill locations for the physical build, and a
reproducible recipe that can be re-applied when a designer ships an updated STL.

---

## 6. Architecture

Single Clojure JVM process, server-rendered UI, with the 3D viewport as the one
client-side island.

```
Clojure JVM (single process)
├─ Ring / Jetty + reitit
├─ hiccup → catalog, loadouts, fleet, paint UI
├─ catalog store: parts, mounts, loadouts, schemes
├─ mesh pipeline: STL parse → weld/index → meshopt simplify → glTF cache
├─ facet grouping + frame derivation for the mount wizard
└─ /mesh/{key}.glb  binary, cached on disk, generated lazily

browser
├─ htmx - all UI
└─ viewport.js - three.js island, driven by HX-Trigger events
```

Cross-platform by construction: run the jar, open localhost. Identical on Linux, macOS
and Windows, with no packaging story to maintain. A thin webview wrapper can be added
later for an app-like window without changing anything below it.

### 6.1 Why the viewport is an island

A three.js canvas is a long-lived stateful object holding GPU buffers. If htmx swaps it,
the WebGL context is destroyed and hundreds of megabytes of geometry must be re-uploaded.
The canvas is therefore marked `hx-preserve` and never targeted by a swap.

The server communicates with it through `HX-Trigger` response headers: a request updates
server-side state, returns the normal hiccup fragment for the UI panel, and carries a
JSON event that the viewport listens for.

```clojure
(defn select-part [req]
  (let [loadout (update-loadout! req)]
    {:status  200
     :headers {"HX-Trigger"
               (json/write-str
                 {"shipyard:set-part"
                  (pr-str {:slot      "prow"
                           :mesh      (str "/mesh/" (mesh-key loadout :prow) ".0.symesh")
                           :transform (mount-transform loadout :prow)})})}
     :body    (h/html (picker-panel loadout))}))
```

The outer object is JSON because htmx parses this header itself and dispatches one event
per key; the payload inside is EDN. TECHNICAL.md §7.1 has the details and the client
side.

The server stays authoritative over loadouts, fleets and schemes - which is what we want,
since all three are persisted anyway.

### 6.2 What stays in JavaScript

Accepted, and inherent to the problem rather than a limitation of the approach:

- Camera orbit, pan and zoom. Cannot round-trip per mouse-move.
- Any manual part-nudging gizmo used during mount authoring.
- **Paint scheme scrubbing.** Dragging a colour slider at 60fps cannot be htmx requests.
  The material updates locally on `input`; `hx-trigger="change"` persists on release.
  This is the feature in most tension with the server-rendered model and carries more
  client-side logic than the rest of the UI.

### 6.3 Frontend dependencies

three.js (~600KB) and htmx are fetched at **build time** at pinned versions and packaged
into the jar's resources. **No vendored copies in the repo** - the vendor directory is
gitignored. `package.json` + `npm ci` gives version pinning and integrity checking for
free; a `tools.build` step copies the dist files into `resources/public/vendor/` before
the uberjar is assembled. three.js ships as an ES module, so an import map and
`<script type="module">` covers loading it. No bundler.

---

## 7. Mesh pipeline

Performance is determined here, not by the renderer.

STL is the worst possible GPU format: no vertex sharing, so every triangle carries three
independent vertices. Pushing raw STL for a heavy scene costs several hundred megabytes
of VRAM and will stutter on integrated graphics. Welding vertices - a closed mesh has
roughly V ≈ T/2 - and using an index buffer cuts that by about two thirds, and quantized
normals reduce it further.

Stages, all server-side:

1. **Scan.** Walk the library root for part folders. Bundle, class and `weapons/` come
   straight from the path; the folder name is the part name. Within a folder, read only
   `unsupported.stl`; record but never display `unsupported-pitted.stl` or `supported.stl`.
   Skip `other/`. Produce part records. Cheap; no mesh parsing.
2. **Preprocess**, lazily on first view of a part:
   - Parse binary STL via `ByteBuffer` - an 84-byte header plus 50 bytes per triangle.
   - Weld and index by quantized position.
   - Recompute smooth normals with a hard-edge angle threshold.
   - Generate LOD tiers with meshoptimizer `simplify` (100% / 25% / 5%).
   - Encode to `.glb` with meshopt compression.
3. **Cache**, content-addressed by SHA of the source file. Re-pitting a hull changes the
   hash, which invalidates the cache automatically - no manual cache management.
4. **Serve** `/mesh/{key}.glb` as static binary.

Preprocessing the whole 118M-triangle library up front is an hours-long job with no
payoff. Lazy generation means the cache only ever holds what has actually been looked at.

**LOD tiers are not needed on day one.** The fleet roster is a list rendering one ship at
a time (§8.4), so the working budget is a single ship - 270k to 600k triangles, which any
GPU handles without help. The pipeline generates the tiers regardless, because doing so
costs almost nothing once meshoptimizer is already in the path, and it means thumbnails
and any future simultaneous-fleet view are cheap to add.

JVM notes: STL parsing, welding and batch preprocessing are CPU grunt work where real
parallelism across cores is a substantial advantage. LWJGL 3 ships meshoptimizer bindings
with prebuilt natives for all three platforms, covering simplification, vertex-cache
optimization and the meshopt encoder - no FFI work required. JOML covers transform math.

**No CSG boolean is required.** Earlier drafts needed it for pit derivation and for
in-app pitting; face picking eliminates the first and §3 defers the second. This removes
the only dependency the JVM was genuinely weak at - pure-Java CSG is BSP-based and would
have been slow and fragile on a 133k-triangle hull. Should the optional `:seeded` path
(§5.2) be built later, bind manifold3d through its C API using Java 22's FFM, or shell
out to a small Python sidecar reusing the existing tooling.

What the wizard needs instead is **coplanar facet grouping** - a flood fill over adjacent
triangles with a normal-dot threshold - plus a 2D convex hull for the roll axis. Both are
modest, self-contained JVM code with no native dependency.

---

## 8. Domain model

Stored as EDN initially; SQLite if query patterns demand it.

### 8.1 Part

```clojure
{:part/id       "human-navy/cruiser/hull"
 :part/source   "Human Navy Fleet Bundle/Cruiser/Hull/unsupported.stl"
 :part/sha      "3f9a…"
 :part/bundle   "Human Navy Fleet Bundle"
 :part/class    :cruiser
 :part/role     :hull                    ; :hull :prow :bridge :antenna :weapon
 :part/name     "Hull"
 :part/tris     133922
 :part/orientation [0.0 0.0 0.0 1.0]     ; source mesh -> canonical pose
 :part/mounts   [ … ]}                   ; sockets if a hull, plug if a module
```

The canonical part pose is `+Y` up, `+Z` forward and `+X` starboard/right. The editor
uses fixed canonical axes: yaw around Y, pitch around X and roll around Z, composing
them in ZXY order. A yaw therefore does not move the axis controlled by a later roll or
pitch input. It persists a normalized quaternion so repeated edits do not accumulate
Euler conversion error. Missing orientation metadata means identity for backward
compatibility.

### 8.2 Mount

```clojure
{:mount/id      :port-1
 :mount/kind    :socket                  ; :socket on hulls, :plug on modules
 :mount/accepts #{:weapon}               ; sockets only
 :mount/pos     [-19.06 4.2 52.0]
 :mount/axis    [-1.0 0.0 0.0]           ; frame +Z, outward from the seat
 :mount/roll    [0.0 0.0 1.0]            ; frame +X
 :mount/magnet  {:r 1.5 :depth 1.0}      ; optional; physical drilling only
 :mount/origin  :picked                  ; :picked :mirrored :seeded - provenance
 :mount/mirror-id :starboard-weapon-1}   ; reciprocal id when symmetry-linked
```

Position and axis come from one picked face (§5.1). Roll is the canonical in-plane twist
reference, with an optional per-mount Twist adjustment. `:mount/magnet` is optional and
carries no weight in assembly - it records where to drill, nothing more.

`:mount/origin` records provenance so lower-confidence entries can be surfaced for
review: `:picked` was chosen directly by the user, `:mirrored` was generated by symmetry
from another mount, `:seeded` was proposed automatically from a pitted/unpitted diff.
A mirrored pair carries reciprocal `:mount/mirror-id` values so either side always edits
or deletes the same pair.

No facet index appears in this record, deliberately - see §5.4.

### 8.3 Assembly draft and loadout

M3 edits one ephemeral draft: a hull and assignments keyed by paths of socket ids and
zero-based section ordinals. Repeating a printable part in different paths is allowed;
repeating a part on its own ancestor chain is a cycle and is rejected. Replacing or
clearing a component removes its descendants. Sockets on components expose further
slots, including turrets on weapons.

Candidates need a role accepted by the socket, exactly one valid plug, and an
available unsupported mesh source. Roles may come from a manual override, a folder,
or filename inference; manual overrides take precedence. Save, preview, edit and
duplicate use the same role and compatibility rules as Assemble. Candidates must
share the root's bundle and class. Single-ship bundles are
self-contained: absent class matches absent class within that same bundle, never
another bundle. Multi-section ships use one manually selected root hull and authored
plugs/sockets joining its sections; filenames never imply those attachments.

The draft has no name or disk persistence. M4 adds the named loadout below and uses
the same path identity, rather than a flat mount-id map that loses repeated/nested slots.

A named ship. Slot assignments plus an optional scheme override.

```clojure
{:loadout/id      #uuid "…"
 :loadout/name    "Dominator-pattern, Voss ram"
 :loadout/hull    "human-navy/cruiser/hull"
 :loadout/slots   {[[:prow 0]]   "human-navy/cruiser/voss-ram-prow"
                   [[:bridge 0]] "human-navy/cruiser/bridge"
                   [[:port-1 0]] "human-navy/cruiser/lance-battery"
                   [[:port-1 1]] "human-navy/cruiser/weapon-battery"}
 :loadout/scheme  #uuid "…"              ; inherited from fleet unless overridden
 :loadout/thumb   "thumbs/….png"}
```

Saved loadouts may be incomplete: a hull alone or any valid subset of its reachable
assignments can be saved, previewed, edited and duplicated. Unassigned mounts are
allowed. Assigned parts must still exist, be available and be compatible with their
mounts; stale assignment paths and malformed catalog data remain errors.

### 8.4 Fleet

An ordered list of loadouts with a default scheme. **Rendered as a list, one ship
displayed at a time** - selecting an entry loads it into the viewport. Not a simultaneous
scene. Thumbnails per entry are desirable but lower priority (M6).

### 8.5 Paint scheme

**v1 supports individual part instances**, with role defaults for convenient reuse.
Two copies of the same weapon may have different materials. Instance identity is the
full assembly slot path (the root hull uses `[]`), paired with the assigned part id.
An instance override applies only while that same part occupies that path; replacing
the part uses its role default. Unmatched overrides remain in the scheme for reuse.
A scheme maps roles and optional individual instances to materials:

```clojure
{:scheme/id    #uuid "…"
 :scheme/name  "Gothic Sector, 2nd Fleet"
 :scheme/roles {:hull   {:base [0.12 0.18 0.32] :metalness 0.3 :roughness 0.6
                         :paint "Kantor Blue"}
                :prow   {:base [0.55 0.45 0.15] :metalness 0.8 :roughness 0.35
                         :paint "Retributor Armour"}
                :weapon {:base [0.20 0.20 0.22] :metalness 0.6 :roughness 0.5}}}
```

`:paint` is an optional free-text range name so a scheme can double as a shopping list.
Mapping to actual manufacturer ranges is not attempted in v1.

An optional `:scheme/instances` map holds entries such as
`{[[:port-1 0]] {:part-id "human-navy/cruiser/lance-battery"
                 :material {:base [0.8 0.1 0.1] :metalness 0.2 :roughness 0.6}}}`.
Instance material wins over group material, then the shared layer palette, role material and neutral studio material. Unmapped
roles are valid. All RGB channels, metalness and roughness are finite numbers in
`[0,1]`; stored RGB uses sRGB, as do the editor's colour swatches. Paint names are
optional free text. A detail brush adds per-face colour, metalness and roughness
overrides. UV/texture painting is outside v1.

Groups are named, ordered sets of instance identities within a scheme. Instances may
belong to multiple groups; the first group in rail order with a material wins. Group
membership matches both full slot path and part id, so replacing a part does not
silently reuse the old membership. Retain unmatched members for reuse. Users create
groups from checked instance rows, rename/delete groups, edit membership and move
groups up/down. Instance rows identify their effective group or role inheritance.
Group creation does not copy a material; a group without a material leaves inheritance
unchanged. Deleting a group preserves instance materials and face details.

A fleet carries a default scheme; individual loadouts may override it for squadron
markings. Named schemes and loadout overrides belong to M5; fleet-default assignment
and the fleet workflow belong to M6. A loadout override takes precedence over a fleet
default; without either the ship uses neutral materials. An unavailable referenced
scheme displays a recoverable warning and neutral materials, preserving its UUID.

The **Paint** workspace follows §9.2. **Paint assembly** explicitly copies Assemble's
current hull and assignments into an independent preview; **Paint ship** does the
same for a selected saved ship. Complete, partial and hull-only assemblies are valid.
Ordinary workspace navigation restores the last paint selection without copying a
different workspace's model. Paint appears after Ship Browser in the selector.

Choose an existing scheme or enter a name and choose **Create scheme** to allocate
its UUID and persist an empty scheme. Creation does not assign it to a loadout.
The left rail offers role defaults, ordered groups and individual populated instances,
identified by their full paths. Selecting a row edits one target in a floating material
inspector over the full-height viewport. **Write to** selects Instance, Role or Group;
Role edits the shared role material and Group offers the selected instance's groups.
**Use inherited material** removes an instance override, revealing its group or role default. Changes to a
shared scheme affect every ship referencing it; explain this beside the controls.
Explicitly choose a scheme in Assemble and Save ship to persist that ship's override.

Material controls preview locally on input, and commit on change/release. A failed
commit reports an error and retains the last durable value; controls remain available
for retry. Leaving Paint restores committed values on return, discarding uncommitted
scrubbing. Pending responses cannot change another selection or activation. Mount
colors temporarily override base colour only; turning them off restores paint,
including the material's metalness and roughness.

Parts may carry reusable, source-bound face regions authored in Part Browser's
Regions tab. Primary and Secondary are permanent shared layer names; users can add,
rename and delete any number of named detail layers. Names defined on any library
part are selectable on every other part without being re-created; assignment adopts
the selected name on that part. Rename and delete remain part-local.
Every face belongs to one layer;
unassigned faces belong to Primary. Assigning Primary or right-dragging erases an
explicit assignment. In both region and freehand painting, right-drag temporarily
erases without changing the selected paint layer, material or mode; Alt retains
camera controls. Freehand erase reveals the inherited material.
Deleting a detail layer on a part returns its faces to Primary without deleting scheme
colors. Regions use the same visible-only, whole-triangle brush selection as detail
painting and persist with the library part. Changed source meshes retain old regions
but cannot display or extend them until the user confirms a reset.

Optional `:scheme/layers` maps shared layer-name strings to complete materials.
Per-face resolution is freehand detail, matching instance material, winning group
material, assigned layer material, Primary material, role material, then neutral.
Legacy color-only detail strokes inherit the resolved region's finish. Region names
match exactly across parts. Scheme defaults are reusable without a preview model;
instance/group and brush tools still require one. Before a scheme is selected or
created, hide unavailable editing controls and explain how to create a named scheme.

Schemes can be deleted after confirmation that identifies referencing saved ships.
Deletion preserves those references and the existing missing-scheme warning. A failed
write keeps the scheme and selection intact. Confirmed deletion clears Paint's selected
scheme. Group management opens when selecting a group and includes rename, membership,
ordering and deletion.

The **Detail brush** paints across instances by default. Turn **Cross instances** off
to confine it to the selected individual instance. **Select** and **Brush** choose the
viewport tool; the Brush rail shows touched instances and their running face counts.
Occluded instances identified behind the brush are explanatory only and are not painted. Its adjustable circular
screen-space footprint selects triangles with at least one visible pixel centre
inside the circle. It fills entire triangles, not partial faces, including at the
footprint boundary. Occluded and back-facing triangles are excluded; there is no
paint-through volume. Drag samples overlap along the pointer path. Left-drag paints
in Brush mode; Alt+drag or Select permits ordinary orbiting. Face deltas flush
periodically during a drag; release commits atomically as one undo step across all
touched instances. **Detail colour**, **Detail metalness** and **Detail roughness**
are captured together for each stroke. Finish controls initially use the selected
target's effective material; changing controls alone does not repaint details.
**Erase to base** removes all three face overrides. Mount colors replaces displayed
colour but preserves face finish without changing saved details. Older colour-only
strokes remain compatible and inherit their instance's current finish.

Detail masks are shared scheme data, scoped to full slot path, part id and source
mesh identity. Changed parts or source meshes never receive an old mask silently;
retain the old data and warn in Paint. **Clear instance details** explicitly removes
it so that the new source can be painted. Undo/redo retains up to 20 detail strokes
in the current server-owned selection, including clear; selection changes reset
history. Failed writes leave durable masks/history untouched and allow retry.
There are no per-stroke or per-layer face limits. A failed chunk or final save restores
the pre-stroke preview on every touched instance and leaves the stroke available for
retry. Buffered parts do not change durable state; leaving or canceling discards them.
Undo is unavailable until the current drag has finished.

---

## 9. UI surfaces

All server-rendered hiccup driven by htmx, except the viewport.

- **Orient** - select library parts and edit their source-to-canonical poses together
  in a preview grid before authoring mounts or assembling ships. See §9.4.
- **Part Browser** - browse individual library parts; filter by bundle, class, role.
  Search by name. This is the user-facing name of the former Browse workspace.
- **Assembly view** - the viewport plus a slot panel. Each slot lists compatible parts,
  filtered by the socket's `:mount/accepts`. Selecting one issues the `HX-Trigger` event
  that swaps geometry in the scene.
- **Mount wizard** (§5.4) - pick a face in the viewport, review the computed frame,
  adjust roll, name it, save. Offers symmetry mirroring on hulls. Surfaces
  `:mount/origin` so mirrored and seeded mounts can be confirmed.
- **Paint editor** - role defaults, individual part materials and visible-face detail brushing against the live model.
- **Fleet roster** - list of loadouts, select to load into the viewport.
- **Ship Browser** - a separate workspace immediately after Assemble in the workspace
  selector, for viewing saved assembled ships. See §9.3.

### 9.1 Thumbnails

Generated by capturing the live viewport with `canvas.toDataURL()` and POSTing the result
back to be stored against the loadout. This avoids a headless GL renderer on the JVM
entirely, which would otherwise be the most annoying part of the feature. The cost is
that a loadout has no thumbnail until it has been viewed once - acceptable, and the
capture can be triggered automatically on save.

### 9.2 Independent workspace state

Every workspace, including Part Browser, Orient, Assemble and Ship Browser, owns its
own selection, transient working state, filters and viewport display settings.
Switching workspaces restores the destination's state on both the server and in the
viewport. A workspace with no selection shows its own empty state. Returning to a
workspace restores its previous selection and settings.

The mount-color toggle belongs to its workspace: changing it affects only that
workspace, and leaving and returning preserves its value and rendered effect.
Shared durable catalog facts remain authoritative; workspace independence does not
create separate copies of authored parts or saved loadouts.

Every workspace transition, whether selected manually or triggered by an action,
updates the workspace selector to match the active workspace, panels and viewport.
Loading or previewing a model in one workspace does not implicitly replace another
workspace's model or assembly draft. Transfers such as Edit and Duplicate are explicit
actions with defined destinations.

### 9.3 Ship Browser and editing saved ships

Ship Browser lists saved loadouts as selectable cards, with browsing and filtering
similar to Part Browser. Filters are by bundle/faction and class, derived from the
saved assembly's root hull. Clicking a card or activating it with the keyboard
displays that saved assembly in Ship Browser, including repeated parts and nested
slots, and preserves the Assemble
workspace's draft. A floating inspector shows the selected assembly's part tree and
color legend, with the hull first and each parent followed by its descendants. The
Ship Browser has its own mount-color toggle, off by default; its setting and rendered
colors are restored when returning to this workspace.

Incomplete saved ships load normally. Their cards show a tag with the number of empty
mounts reachable on the hull and attached parts, including nested and capacity-expanded
mounts. Do not count hypothetical mounts on unassigned parts. Complete ships have no
empty-mount tag; a malformed tree has no reliable count.

Each card provides three separate actions:

- **Edit** opens Assemble, updates the workspace selector, and loads the selected
  saved ship for editing. Its saved identity is retained so saving edits updates that
  ship; entering Edit alone does not write changes to disk.
- **Duplicate** opens Assemble, updates the workspace selector, and loads an
  independent draft with the same hull, assignments and optional scheme override.
  The name is pre-populated with exactly `<original name> - Copy` and remains editable.
  Duplicate only pre-populates Assemble; it creates no saved entity. Saving creates
  a new loadout identity and cannot overwrite the source ship.
- **Delete** asks for confirmation naming the saved ship, then removes only that saved
  identity. Library parts and other saved ships remain unchanged. Deleting the displayed
  ship clears its preview and inspector. If Assemble edits that ship, retain its work
  as an unsaved draft whose next Save creates a new identity. Missing library parts do
  not prevent deletion; a failed write preserves the record and both workspaces.

Edit, Duplicate and Start assembly ask to discard an existing unsaved assembly or
cancel before replacing it. Unsaved means a new or duplicate draft with a hull, or
content (including name and scheme) differing from its saved record. An unchanged
saved assembly and an empty workspace need no prompt. Cancel preserves the draft,
workspace and viewport; confirmation applies to the current draft revision.

Invalid or unavailable saved data produces an actionable error without destroying
the existing draft or preview. Merely returning to Assemble resumes its own draft;
only an explicit Edit or Duplicate action replaces it with the selected saved ship.
Ship Browser does not require fleet ordering, fleet default schemes or thumbnails;
those remain in M6.

### 9.4 Orient workspace

Orient provides bulk part-orientation authoring. It establishes which way a source
mesh faces in Shipyard's canonical coordinates (§8.1): `+Y` up, `+Z` forward and `+X`
starboard/right. It edits reusable part metadata, so saved poses apply wherever those
parts are subsequently viewed or assembled. Source STL files and existing mount
records remain unchanged. Orient appears before Part Browser in the workspace selector.

**Select.** A table fills the workspace and supports filters by bundle, class, role,
name and saved-orientation status (any, unset or saved). Each row shows the part name,
role, class, saved yaw/pitch/roll and orientation status. An explicitly saved identity
pose counts as Saved; a part without saved orientation is Unset. Parts that cannot be
previewed remain visible as No preview, with selection disabled. No matches produces
an explicit empty state.

Selection persists across filter changes, including selected parts hidden by the
current filters. The selected count describes the whole selection. **Render selection**
is enabled only for a nonempty selection and replaces the table with a workspace-width
grid. Each selected previewable part has its own named preview card, independently
framed from a common viewing direction. Preparing or failed meshes show their status;
scrolling and resizing must keep each model within its own card.

**Preview.** One toolbar applies to every loaded model in the selection:

- Pitch (X), Yaw (Y) and Roll (Z) **−/+** buttons apply a relative turn around the fixed
  canonical axis. Choose a 1°, 15° or 90° step; the initial step is 90°.
- The numeric field for each axis sets that absolute Euler angle on each model,
  preserving its other two Euler components. This is distinct from a relative turn.
  Finite values preview locally when committed; invalid/non-finite values must not
  corrupt the pose.
- **Copy first** copies the first loaded part's current complete pose to every loaded
  part. The order is stable by part id, not mesh-load completion order.
- **Reset** restores each part's own last successfully saved pose and clears its dirty
  state. For a part without a saved pose that is identity. Bulk Reset is a preview
  action; the single-part editor's Reset instead persists the source orientation.

**Save.** Preview changes do not write to disk. **Save orientations** is enabled when
there are dirty poses and saves only those parts. Success establishes a new saved
baseline for each saved part. A partial failure reports the failed parts and retains
their dirty previews for retry; successful writes remain saved. Saving preserves other
part metadata and mount records. **Back to table** ends the grid preview, discards its
unsaved changes and releases its rendering resources while preserving the selected ids.

**Workspace ownership.** Merely switching to another workspace is not Back to table
or Reset. Under §9.2, Orient must retain its filters, selection, table/grid mode,
rotation step, current poses, saved baselines, dirty state and display
settings. Returning restores that session and synchronizes the selector. Unsaved poses
must not change another workspace's model or the durable catalog.

TECHNICAL.md §12.6.1 defines the implementation boundaries and required verification
for this workflow, including workspace-session restoration under §9.2.

---

## 10. Delivery planning

Milestone scope, progress, and outstanding work live in the
[Forgejo milestones](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/milestones).

## 10.1 CI

Forgejo Actions, Linux, Windows and Apple Silicon macOS runners.

The JVM is portable but **the native dependencies are not** - LWJGL ships
platform-specific meshoptimizer natives, and that is precisely the layer most likely to
break silently on one platform. Windows and macOS runners earn their place by exercising
the mesh pipeline end to end on their actual natives, not merely by compiling.

Per platform: build, unit tests, and a pipeline smoke test that parses a known STL, welds
it, generates LOD tiers, encodes a `.glb`, and asserts triangle and vertex counts against
fixtures. Committing a small fixture STL rather than depending on the 19 GB library keeps
CI self-contained.

---

## 12. Documentation requirements

Two documents are deliverables of this project, not artefacts of it, and both carry a
standing obligation to stay current.

### 12.1 The user manual

`docs/MANUAL.md` describes how to run Shipyard and get a ship on screen, written for
someone who owns STL models and wants to preview them - not for someone reading the code.

It documents **supported behavior only**. Planned features and delivery status belong in
Forgejo issues and milestones, so a reader never mistakes a promise for a capability.

**Every change to user-facing behaviour updates the manual in the same pull request.**
Not in a follow-up or at the end of a milestone.

Scope: installation, pointing Shipyard at a library, every user-facing workflow, and
troubleshooting for failures a user can actually hit. Where the software has a rough
edge - roles being guesses, parts that only ship as `supported.stl`, malformed STLs in
the wild - the manual says so plainly rather than letting the user discover it and assume
the software is broken.

### 12.2 The README

`README.md` serves contributors: what Shipyard is, the required toolchain, how to build,
run, test, lint and format it, and where the other documents are.

**Every change to how the project is set up, run, or tested updates the README in the
same pull request.** A README that lags is worse than none, because people trust it and
lose an hour before doubting it.

### 12.3 Why these are requirements

This project's specifications have already been wrong three times in ways only
measurement caught - the claim that no ASCII STLs existed, that escorts were uniformly
whole ships, that welding would reach `V ≈ T/2`. Documentation drifts the same way and
more quietly, because nothing fails when it does. Making currency a merge obligation is
the only mechanism that reliably works; a periodic documentation pass is a promise to
future-you that future-you will not keep.

### 12.4 Behavior changes require E2E coverage

Every behavior change must include new or updated E2E tests in the same pull request.
Tests exercise the changed workflow through the running application and assert its
observable results, including state preservation and failure behavior where relevant.
Unit and integration tests supplement this coverage. Workspace changes must verify
the selector, server-owned model state and rendered viewport agree, including after
leaving and returning. See TECHNICAL.md §10.3 and §14.4.
