# Shipyard

Preview and assemble Battlefleet Gothic miniatures from STL part libraries.

A cruiser is not one file. It is a hull, one of twelve interchangeable prows, a bridge,
two antennae, and weapon modules that repeat across several mount points. The
combinations run into the thousands and there is no way to see one before committing
hours of resin to it - and prows are distinguished by silhouette details that only read
once mounted. Shipyard closes that loop: pick parts, see the assembled ship, decide
before printing.

Current work and delivery planning live in the
[Forgejo milestones](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/milestones).

## Requirements

These tools are needed to build from source. Desktop packages include Java 25
and do not require Java, Clojure or Node to be installed separately.

| | version | why |
|---|---|---|
| **JDK** | **25** | Canonical. Not optional - see below. |
| Clojure CLI | 1.12.4.1618+ | |
| Node | 24+ | shadow-cljs and the npm-managed frontend dependencies |

On Windows, install the [x64 Visual C++ Redistributable](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)
for Datalevin's C++ and OpenMP native dependencies. CI checks these DLLs before
loading Datalevin and installs the runtime when the runner has administrator access.

**Java 25 is the canonical JDK for this project.** It is not a floor to be negotiated
down. The `:run` and `:test` aliases pass `--sun-misc-unsafe-memory-access=allow`, which
does not exist before JDK 23 - an older JVM refuses to start rather than ignoring it -
and `--enable-native-access=ALL-UNNAMED`, which LWJGL needs to load its native
meshoptimizer bindings without warnings. CI pins 25 on every job and the uberjar declares
`Enable-Native-Access` in its manifest. Develop on 25.

The **Browse…** and **Import ZIP…** buttons open Swing folder and ZIP selectors on Shipyard's local
desktop using Java's `java.desktop` module; no separate dialog packages are needed.
Use a browser on that same computer. A desktop-capable Java runtime and access to
the graphical session are required. ZIP paths can be typed into the selector’s filename
field. Library-folder path entry also works on headless servers.

## Quick start

For the desktop app, download the package for your platform from
[Releases](https://github.com/MeltzgSoft/shipyard/releases):

| Platform | Package |
|---|---|
| Linux x64 | AppImage or Debian package |
| Windows x64 | NSIS `.exe` installer |
| Apple Silicon macOS | `.dmg` containing `Shipyard.app` |

Launch Shipyard. Its backend binds the first available loopback port starting
at 8080 and opens the desktop window at the reported address. Closing the window
stops the backend; launching the app again focuses the existing instance.
Windows still needs the x64 Visual C++ runtime described above.

To run the backend from source instead:

```bash
git clone ssh://git@forgejo.tail943578.ts.net/MeltzgSoft/shipyard.git
cd shipyard
npm ci                      # frontend deps: three.js, htmx
clojure -T:build uber       # builds the CLJS bundle and the uberjar
java -jar target/shipyard-0.1.0-SNAPSHOT.jar
```

Then open <http://127.0.0.1:8080>. It has no library until you give it one: the
library panel asks for the folder your models are in, remembers it, and scans it
without a restart. See the [user manual](docs/MANUAL.md).

## Desktop development and release

Linux desktop builds need `binutils` and `xz-utils`
(`sudo apt-get install binutils xz-utils` on Debian/Ubuntu). Java's
`jlink --strip-debug` uses `objcopy`, and Debian packaging uses xz compression.

On a supported native build platform, install the desktop tools and build:

```bash
npm ci --prefix electron
clojure -T:build desktop
```

This builds the release frontend, uberjar and CLJS Electron main process, links
a Java 25 runtime, and produces installers in `electron/dist/`. Use
`clojure -T:build desktop :dir true` for an unpacked app. Packaging stages metadata
under `target/desktop/`; it does not rewrite tracked npm files. The host must match
the target because the bundled runtime is native code.

For a checkout window, build the jar and shell first:

```bash
clojure -T:build uber
npx shadow-cljs release desktop
npm start --prefix electron
```

The checkout shell uses the exact default jar path, not whichever jar was built
most recently. The installed shell requires its bundled jar and runtime.
Run one backend per database; stop a separate CLI server before opening the
desktop app with the same data directory.

`.github/workflows/desktop.yml` builds the shared uberjar and released CLJS shell
once, then packages them on GitHub-hosted Linux, Windows and Apple Silicon macOS
runners with each OS's Java runtime. The matrix calls `clojure -T:build
package-desktop` against that exact prebuilt payload; it does not repeat AOT or
frontend compilation. It tests the owned-child protocol using each bundled
runtime and drives a real packaged Electron window on Linux.

Mirror branch pushes and installer-only manual runs retain installers as GitHub
Actions artifacts and publish nothing. A `vX.Y.Z` tag sets `SHIPYARD_VERSION`,
and only after every platform build and test succeeds does the workflow attach
the four desktop packages and `SHA256SUMS` to a GitHub Release. A manual smoke
build defaults to `installers_only: true`; publishing manually requires a version
tag ref and `installers_only: false`. The development uberjar remains available
as a build task. Ordinary repository tests continue on Forgejo.

Signing uses electron-builder's signing environment configuration when supplied.
Without signing credentials, packages are unsigned; macOS and Windows apply
their normal checks for unsigned downloaded apps.

## Development

The mesh pipeline needs **a platform alias for the LWJGL natives** -
`:natives-linux` below; substitute `:natives-windows` or
`:natives-macos-arm64`. Commands without one below do not need one.

```bash
mkdir -p resources/public/js && cp node_modules/htmx.org/dist/htmx.min.js resources/public/js/   # once per clone - see below
clojure -M:natives-linux:run            # server, no jar
npx shadow-cljs watch viewport          # hot-reloaded CLJS, in a second terminal
clojure -M:dev:natives-linux -m nrepl.cmdline  # REPL; then (go), (reset), or (halt)

clojure -M:test:natives-linux                       # all suites
clojure -M:test --focus :unit                       # pure functions only, sub-second
clojure -M:test:natives-linux --focus :integration  # filesystem, natives, HTTP
npx shadow-cljs compile viewport                    # viewport bundle, with test hooks
clojure -M:test:natives-linux --focus :e2e          # headless browser
npx shadow-cljs compile test && node target/js/node-tests.js  # CLJS node unit tests

clojure -M:natives-linux:canary         # data-quality probe over the real library
clojure -M:m2-human-navy-cruiser-proof --root /tmp/human-navy-cruiser-copy --out m2-human-navy-cruiser-proof.edn
clojure -M:natives-linux:escort-probe   # on-demand escort geometry classifier report
clojure -M:natives-linux:benchmark --root /path/to/models --machine "CPU; RAM; GPU; storage"
clojure -M:cljfmt check src test dev build.clj    # `fix` to apply
clojure -M:clj-kondo --lint src --lint test --lint dev --lint build.clj
clojure -M:outdated                     # dependency freshness, deps.edn + package.json
```

The Human Navy Cruiser proof is an on-demand check over proprietary user-owned STL data,
so it is not a CI gate and it should not run against your only copy. Materialize a
temporary Shipyard-style Cruiser library first; the proof command writes
an isolated database under its cache directory, reopens it, checks the M3 slot/attachment contract,
audits every authored mount against its source surface, and writes an EDN report. A
blocked report deliberately identifies mounts that need reauthoring rather than guessing
their mating geometry.

CLI tools use small Integrant graphs in `resources/systems/`: `workers.edn` for the
canary/job measurements, `store.edn` for saved-root lookup, `library.edn` for the
escort probe and scan measurements, and `catalog.edn` for the Cruiser proof.
They start/stop through `shipyard.system`; explicit tool roots do not change the
application's saved selection. HTTP, browser and benchmark fixtures have their
own graphs in `test/clj/shipyard/systems/`, with temporary runtime paths and
port 0 where a server is needed. The shared assembly/Ship Browser fixture uses
`assembly.edn` and adds `server.edn` only for browser tests; its setup cleans up
if library construction, initialization or fixture authoring fails.

The benchmark is the on-demand, machine-labelled measurement behind TECHNICAL.md §11;
it is deliberately not a CI gate. It creates isolated databases for fresh scan indexes
and mesh caches under the system temp directory, drives a hardware Chromium window
for ten seconds, runs the whole-library canary with four threads, and writes `benchmark.edn`. Build the dev viewport
bundle first (`npx shadow-cljs compile viewport`). Use `--viewport-mode swiftshader` only
for a CPU-renderer comparison; it does not measure the hardware viewport budget.

The paint preparation responsiveness probe uses a deterministic 36,480-triangle hull
with repeated assembly parts and records cold/warm buffer preparation time and the
longest measured CPU chunk to `/tmp/shipyard-paint-profile.edn`. Build the dev viewport
first. The ordinary E2E run uses Chromium; an on-demand Firefox comparison requires
Playwright's Firefox browser (`com.microsoft.playwright.CLI install firefox`):

```bash
clojure -M:test:natives-linux --focus shipyard.e2e.paint-preparation-test
clojure -J-Dshipyard.paint.profile.browser=firefox -M:test:natives-linux --focus shipyard.e2e.paint-preparation-test
```

These observations depend on browser, GPU, and host load. They separate bounded CPU
material work from an event-loop gap measurement, which also includes GPU/frame
scheduling and is not a standalone main-thread CPU profile.

The on-demand job-memory benchmark uses a separate JVM and temporary stores. Choose
an explicit heap limit. It reports retained queue heap after GC, sampled rendering
heap peaks, GC time, elapsed time and worker errors as EDN records; these are machine-
and workload-specific measurements, not CI thresholds.

```bash
clojure -J-Xmx2g -M:natives-linux:jobs-benchmark thumbnail-queue '{:faces 50000 :counts [32 128]}'
clojure -J-Xmx2g -M:natives-linux:jobs-benchmark queue '{:faces 50000 :counts [1 4 16 32]}'
clojure -J-Xmx1g -M:natives-linux:jobs-benchmark schedule '{:file "/path/to/mesh.2.symesh" :jobs 300 :threads 2 :queue-size 4096}'
clojure -J-Xmx2g -M:natives-linux:jobs-benchmark active '{:file "/path/to/mesh.0.symesh" :painted? true :threads [1 2 4] :jobs 4 :runs 2}'
```

`thumbnail-queue` drives the actual class-thumbnail handler with synthetic region data;
`queue` models closures retaining face maps (`:faces 0` models lightweight work).
`schedule` reads an existing mesh tier, warms rendering five times, then reports
300 independently oriented PNG jobs, admission/rejection, queue wait percentiles,
read/decode time, render time, worker CPU time and an interactive probe's completion
latency. Compare identical geometry, heap limits and warm-up on an otherwise idle
machine; timings are observations, not CI gates.

The common pool defaults to two workers and 4,096 pending jobs. Configure
`:shipyard.jobs/pool` in `resources/config.edn`; `:interactive-reserve` defaults to the
smaller of 32 or one quarter of pending capacity. Bulk imports leave those slots for
interactive work. An import batch exceeding available capacity fails atomically;
increasing workers also increases active geometry/rendering memory.

`active` reads an existing mesh cache file without modifying it and renders independent
copies with synthetic face assignments. Its heap peaks include uncollected garbage;
use repeated runs and worker errors alongside the timing rather than treating peaks
as retained memory. Deliberately testing a small heap can produce OOMs in this isolated
benchmark process.

Report exports use ordinary file writes and create missing parent directories. An
interrupted write can leave a partial report; rerun the command to regenerate it.

`clojure -J-Xmx2g -M:dev -m shipyard.emission-benchmark 100000` measures synthetic
source-space emissive preparation and repeated region grouping, including thread
allocation. It reports observations rather than CI thresholds; browser palette changes
combine prepared directional summaries and perform no triangle aggregation.

**htmx is copied, not bundled** - `clojure -T:build uber` does it for you, but the dev
commands above do not, so a fresh clone needs it once. The `mkdir` is not decoration:
`resources/public/js/` is gitignored build output, so it does not exist until something
creates it. Skip it and the page still renders
and the canvas still loads; the library panel simply never populates, which reads exactly
like a server bug. Why it is delivered separately from the CLJS bundle: TECHNICAL.md §8.

`deps.edn` declares `org.lwjgl/lwjgl` and `org.lwjgl/lwjgl-meshoptimizer`,
which are the Java API jars; the `.so`, `.dll` and `.dylib`
are separate Maven artifacts carrying a
platform classifier, and they live only in those aliases. Nothing fails to download - the
classpath resolves cleanly and simply contains no native binary.

**It fails late, which is what makes it confusing.** The natives load the first time a
mesh is actually preprocessed, so a process starts, reports itself healthy, and only then
dies on `Failed to locate library: liblwjgl.so` - deep in `shipyard.mesh.lod`, where it
reads as a bad STL rather than as a missing command-line alias. Under `-M:test` that is 13
failures across the cache and LOD suites; under `-M:run` it is whatever first asks for
geometry.

No alias is picked for you, because Forge CI deliberately executes the unit and integration
suites with `:natives-linux`, `:natives-windows`, and `:natives-macos-arm64` on their native
Linux, Windows, and Apple Silicon macOS runners. The uberjar is the exception and needs
nothing: `clojure -T:build uber` bundles all four classifiers, which is why the Quick start
above is a plain `java -jar`.

CI prints each JVM and browser test name so a slow or stalled run identifies the active test.
The Linux E2E job uses a verified, 2 GiB tmpfs at `/shipyard-test-tmp` for the test JVM's
temporary fixtures, databases, mesh caches and native extraction. This avoids container
overlay I/O delays during browser readiness checks. Integration tests retain disk-backed
temporary storage on each platform; local test commands keep the system temporary directory.
The README command check isolates configuration, cache and database directories and
resolves the server command's dependencies before timing startup. It allows up to five
minutes for readiness on a busy CI host, stops early if the server exits, and then
checks the shell, htmx, library and fixture geometry.

Tests come in three levels separated by **what they are allowed to touch**, not by size:
unit touches nothing outside the process, integration gets the filesystem and natives,
e2e drives a real browser. See TECHNICAL.md §10.

Every behavior change includes new or updated E2E tests in the same pull request
(SPEC.md §12.4). Workspace behavior must satisfy the state-isolation and transition
acceptance scenarios in TECHNICAL.md §14.

The e2e suite brings its own browser. Fetch it once per machine:

```bash
java -cp "$(clojure -Spath -M:test)" com.microsoft.playwright.CLI install --with-deps chromium
```

Playwright downloads a Chromium versioned with the library into
`~/.cache/ms-playwright`, so nothing needs installing system-wide and there is no
`chromedriver` to keep in step. `--with-deps` also installs the shared libraries Chromium
needs, and wants `sudo`; drop it if they are already present.

It also needs both gitignored front-end assets in place: the viewport bundle from the
**dev** build - only that one defines the `window.__shipyard` introspection hook the
assertions read - and htmx, which is copied out of `node_modules` rather than bundled.
Without htmx the page renders and nothing ever loads the library, which reads exactly like
a server bug; the suite checks for both up front and tells you which command to run. The
suite is hermetic: its library, database (including settings and scan index), and mesh
cache live in temporary directories, so it never touches your real data.

Authored metadata, application-managed settings and the derived scan index use one
Datalevin database at `$XDG_DATA_HOME/shipyard/database`,
defaulting to `~/.local/share/shipyard/database`. Configure
`:shipyard.store/db {:data-home "/path/to/data"}` or `{:directory "/exact/database"}`
in user `config.edn`. The library index, catalog, loadouts and schemes share this store;
configure its location once. Run one Shipyard process per database. Stop the application before
copying the entire database directory for backup or restore.

Datalevin's native library is pinned to 1.1.5 to fix a compressed overflow-page
deletion error (`MDB_PROBLEM`) that can prevent further painting. Its DLMDB format
is v2.

Back up both the database and source STLs;
copying an STL folder alone no longer carries authored metadata. Scan entries remain
rebuildable derived data inside the database; the `.symesh` cache remains disposable
files. System/bootstrap configuration stays in `config.edn`, and report exports remain
files. An existing `library.edn` selection is imported only when the database has no
selection; choose subsequent changes through **Library folder** in the application.
Database native binaries support Linux x86-64/ARM64, Windows x86-64 and macOS ARM64; this dependency does not ship macOS Intel binaries.

The Paint brush sends new faces during each drag at the interval configured by
`:shipyard.paint/db {:paint/flush-interval-ms 120}` (milliseconds, positive integer).
The server buffers these parts and saves the entire drag atomically on release.

### Git hooks

Install once per clone - git does not do this for you:

```bash
git config core.hooksPath .githooks
```

`pre-commit` runs cljfmt, clj-kondo, the JVM unit suite and the CLJS node unit suite,
so a red CI run is never the first you hear of a formatting problem. It runs every
check before reporting, so one commit attempt tells you everything to fix. Integration
and e2e stay in CI deliberately: a hook slow enough to be annoying is a hook people
bypass with `--no-verify`.

## Documentation

| document | audience |
|---|---|
| [docs/MANUAL.md](docs/MANUAL.md) | **Users.** How to run Shipyard and get a ship on screen. |
| [SPEC.md](SPEC.md) | What Shipyard is for, what it deliberately is not, and why. |
| [TECHNICAL.md](TECHNICAL.md) | Implementation design: layout, storage, mesh pipeline, HTTP, testing. |

**These are requirements, not courtesies.** Any change to user-facing behaviour updates
`docs/MANUAL.md` in the same pull request; any change to how the project is set up, run,
or tested updates this README in the same pull request. Documentation that lags the code
is worse than none, because people trust it. See SPEC.md §12.

Pull request descriptions follow `.forgejo/PULL_REQUEST_TEMPLATE.md`, and CI checks that
they do - that the sections are present, that "What this changes" says something, and
that the checklists have been engaged with rather than submitted untouched. It checks
engagement, not prose. Run it yourself before pushing:

```bash
.forgejo/scripts/check-pr-description.sh my-description.md
```

## Project layout

```
src/clj/     JVM only          src/cljc/    both runtimes      src/cljs/    browser only
resources/   config.edn, static assets      test/{clj,cljc}/   by level
```

Source roots are split by runtime so `.cljs` never lands on the JVM classpath or inside
the uberjar. Full layout in TECHNICAL.md §2.

## License

No license is granted for this repository. The third-party purchased STL assets it
operates on are not part of the repository.
