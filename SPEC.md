# Shipyard

A desktop application for previewing Battlefleet Gothic miniatures assembled from
existing STL part libraries - choose a hull, prow, bridge and weapon loadout, see the
result rendered as a complete ship, orbit and zoom it, design a paint scheme for it,
and keep the result as a named loadout in a fleet roster.

Status: specification. No code yet.

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

Library root: `~/Documents/3D_models/BFG`, ~15 bundles, 19 GB, 3,160 STLs.

**One folder per part, holding all of that part's variants:**

```
<Bundle>/[<Class>/][weapons/]<Part Name>/
    unsupported.stl              the geometry Shipyard reads
    unsupported-pitted.stl       where magnet pits have been cut (4 across the library)
    supported.stl                print-prepared, with support scaffolding - ignored
```

- **The folder name is the part identity.** No parsing a part name out of a filename.
- **Variant selection is a filename lookup**, not path archaeology: read
  `unsupported.stl`, prefer `unsupported-pitted.stl` when present, never read
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
- **Escorts appear to be pre-combined whole ships.** `Cyanide Prow Rapier/` reads as
  hull-class x prow-variant already merged, ~36 such parts in Human Navy alone.
  **Unverified** - see Risks. If confirmed, escorts are a pick-one list, not an assembly.

`other/` directories contain Lychee `.lys` project files (1,489 across the collection),
named per configuration, plus occasional `README.txt` files carrying assembly notes in
prose. Neither is consumed by Shipyard v1, but the `.lys` names enumerate combinations
the designers intended and may be useful later for seeding the catalog.

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
| Facet's longest convex-hull edge | **Roll** (the frame's +X) |

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

Given socket frame `S` on the hull and plug frame `P` on the module, both with +Z
pointing outward from their mating surfaces, the module's world transform is:

```
M = S · Tz(g) · Rx(π) · P⁻¹
```

`Rx(π)` flips the plug to face the socket, mapping its +Z to the socket's -Z while
preserving the +X roll reference. `Tz(g)` is an optional gap along the socket axis,
default 0 - the virtual model mates flush, but a small positive `g` can represent the
physical standoff introduced by magnets if that turns out to matter visually.

### 5.4 The face-picking wizard

The primary authoring interaction. Two flows, differing only in how many faces are picked.

**For a module** (weapon, prow, bridge, antenna) - pick **one** face, its mating back
surface. Shipyard shows the derived axis as an arrow and the roll as an in-plane
indicator; the user confirms or adjusts, names it, saves. One plug per module.

**For a hull** - pick **N** faces, one per seat: each weapon shelf, the prow cap, the
bridge deck. Each gets an id and an `accepts` role. Multiple sockets per hull.

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
3. The viewport draws the highlight overlay and the axis/roll indicators.

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
                 {:shipyard/set-part
                  {:slot      "prow"
                   :mesh      (str "/mesh/" (mesh-key loadout :prow) ".glb")
                   :transform (mount-transform loadout :prow)}})}
     :body    (h/html (picker-panel loadout))}))
```

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
   straight from the path; the folder name is the part name. Within a folder, prefer
   `unsupported-pitted.stl`, fall back to `unsupported.stl`, never read `supported.stl`.
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
 :part/source   "Human Navy Fleet Bundle/Cruiser/Hull/unsupported-pitted.stl"
 :part/sha      "3f9a…"
 :part/bundle   "Human Navy Fleet Bundle"
 :part/class    :cruiser
 :part/role     :hull                    ; :hull :prow :bridge :antenna :weapon
 :part/name     "Hull"
 :part/tris     133922
 :part/mounts   [ … ]}                   ; sockets if a hull, plug if a module
```

### 8.2 Mount

```clojure
{:mount/id      :port-1
 :mount/kind    :socket                  ; :socket on hulls, :plug on modules
 :mount/accepts #{:weapon}               ; sockets only
 :mount/pos     [-19.06 4.2 52.0]
 :mount/axis    [-1.0 0.0 0.0]           ; frame +Z, outward from the seat
 :mount/roll    [0.0 0.0 1.0]            ; frame +X
 :mount/magnet  {:r 1.5 :depth 1.0}      ; optional; physical drilling only
 :mount/origin  :picked}                 ; :picked :mirrored :seeded - provenance
```

Position, axis and roll are all derived from one picked face (§5.1). `:mount/magnet` is
optional and carries no weight in assembly - it records where to drill, nothing more.

`:mount/origin` records provenance so lower-confidence entries can be surfaced for
review: `:picked` was chosen directly by the user, `:mirrored` was generated by symmetry
from another mount, `:seeded` was proposed automatically from a pitted/unpitted diff.

No facet index appears in this record, deliberately - see §5.4.

### 8.3 Loadout

A named ship. Slot assignments plus an optional scheme override.

```clojure
{:loadout/id      #uuid "…"
 :loadout/name    "Dominator-pattern, Voss ram"
 :loadout/hull    "human-navy/cruiser/hull"
 :loadout/slots   {:prow     "human-navy/cruiser/voss-ram-prow"
                   :bridge   "human-navy/cruiser/bridge"
                   :port-1   "human-navy/cruiser/lance-battery"
                   :port-2   "human-navy/cruiser/weapon-battery"}
 :loadout/scheme  #uuid "…"              ; inherited from fleet unless overridden
 :loadout/thumb   "thumbs/….png"}
```

### 8.4 Fleet

An ordered list of loadouts with a default scheme. **Rendered as a list, one ship
displayed at a time** - selecting an entry loads it into the viewport. Not a simultaneous
scene. Thumbnails per entry are desirable but lower priority (M6).

### 8.5 Paint scheme

**v1 is per-part colour** - hull one colour, prow another, weapons a third. Nearly free,
since the parts are already separate meshes with separate materials. A scheme maps part
role to a material:

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

**Assumption, flagged:** per-part granularity is assumed sufficient for v1. Per-region
painting - spine, engine block, panel lines separately - is a plausible later refinement
but requires either region definitions or true surface painting, and STL carries no UVs.
Confirm before building on this.

A fleet carries a default scheme; individual loadouts may override it for squadron
markings.

---

## 9. UI surfaces

All server-rendered hiccup driven by htmx, except the viewport.

- **Library browser** - filter by bundle, class, role. Search by name.
- **Assembly view** - the viewport plus a slot panel. Each slot lists compatible parts,
  filtered by the socket's `:mount/accepts`. Selecting one issues the `HX-Trigger` event
  that swaps geometry in the scene.
- **Mount wizard** (§5.4) - pick a face in the viewport, review the computed frame,
  adjust roll, name it, save. Offers symmetry mirroring on hulls. Surfaces
  `:mount/origin` so mirrored and seeded mounts can be confirmed.
- **Paint editor** - per-role swatches against the live model.
- **Fleet roster** - list of loadouts, select to load into the viewport.

### 9.1 Thumbnails

Generated by capturing the live viewport with `canvas.toDataURL()` and POSTing the result
back to be stored against the loadout. This avoids a headless GL renderer on the JVM
entirely, which would otherwise be the most annoying part of the feature. The cost is
that a loadout has no thumbnail until it has been viewed once - acceptable, and the
capture can be triggered automatically on save.

---

## 10. Milestones

| | Deliverable |
|---|---|
| **M1** | Library scan, catalog, mesh pipeline, single-part viewer. Pick any STL, see it, orbit it. Proves the pipeline and the performance assumptions. |
| **M2** | Mount wizard. Face picking, frame derivation, symmetry mirroring, mount persistence. |
| **M3** | Assembly. Slot panel, compatibility filtering, assembled Human Navy Cruiser in the viewport. |
| **M4** | Named loadouts: save, load, list, duplicate. |
| **M5** | Paint schemes, per-part. |
| **M6** | Fleet roster list, with thumbnails via canvas capture. |

M2 and M3 were one milestone in an earlier draft. Splitting them reflects that the wizard
is now the centrepiece rather than a fallback: it is independently useful and
independently testable, and assembly is a thin layer of transform math on top of it.

**First scope is the Human Navy Cruiser** - one hull, twelve prows, bridge, two antennae,
six weapon modules. Enough variety to prove the catalog schema and the wizard before
scaling to 19 GB, and its pitted variant makes it a convenient test case for the optional
`:seeded` path later.

**Assumption, flagged:** first scope was recommended but not explicitly confirmed.

## 10.1 CI

Forgejo Actions, Linux and Windows runners.

The JVM is portable but **the native dependencies are not** - LWJGL ships
platform-specific meshoptimizer natives, and that is precisely the layer most likely to
break silently on one platform. A Windows runner earns its place by exercising the mesh
pipeline end to end on Windows natives, not merely by compiling.

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

It documents **what the software does today.** Features that are specified but not built
are marked as such with the milestone that will deliver them, so a reader can always tell
the difference between a promise and a capability. That distinction is the whole point: a
manual describing unbuilt features is a lie with a table of contents.

**Every change to user-facing behaviour updates the manual in the same pull request.**
Not in a follow-up, not at the end of a milestone. A milestone is not complete while its
manual section still says *Not yet built*.

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

## 13. Risks and open questions

- **Escorts may not be assemblies.** §4 infers that escort STLs are pre-combined whole
  ships from filename patterns and file sizes. Not verified by inspecting geometry. If
  wrong, the escort part of the catalog schema is wrong. Verify before M2 touches escorts.

- **Roll is still underdetermined on rotationally symmetric faces.** A circular prow cap
  has no longest hull edge, so §5.1's roll rule degenerates. Fall back to world-up
  projected into the face plane and expose a roll slider in the wizard. Affects a
  minority of mounts, but the wizard must handle it rather than producing a silently
  arbitrary orientation.

- **Facet grouping may not match user intent.** A "flat" hull seat may be subtly bowed,
  or carry alignment teeth or a notch, so a strict coplanarity threshold fragments it
  into several facets and a loose one bleeds into neighbouring surfaces. The existing
  tooling hit exactly this and its guidance was to render the candidates and ask rather
  than guess from area alone. Expect the threshold to need tuning, and expect to need a
  merge-adjacent-facets affordance in the wizard.

- **Non-watertight source meshes.** Only relevant if the optional `:seeded` path (§5.2)
  is built - `trimesh.boolean.difference` raises `ValueError: Not all meshes are
  volumes!` on such input, requiring the manifold3d direct API. Face picking is
  unaffected: it needs no booleans and works on any mesh that loads.

- **Cache growth.** Lazy `.glb` caching bounds this to what has been viewed, but there is
  no eviction policy specified. Add one if it becomes a problem.

- **Per-part paint granularity** may prove too coarse once used in anger (§8.5).

- **Mount authoring is the real cost centre.** The pipeline, viewer and UI are all
  tractable. The open-ended work is populating mounts across the collection, and it
  scales with how many models are actually wanted rather than with library size. Face
  picking makes each mount cheap and symmetry mirroring halves the count, but the total
  is still bounded only by appetite. Ship M2 early and author a real hull with it before
  committing to the rest of the plan.
