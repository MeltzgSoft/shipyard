# Shipyard

Preview and assemble Battlefleet Gothic miniatures from STL part libraries.

A cruiser is not one file. It is a hull, one of twelve interchangeable prows, a bridge,
two antennae, and weapon modules that repeat across several mount points. The
combinations run into the thousands and there is no way to see one before committing
hours of resin to it - and prows are distinguished by silhouette details that only read
once mounted. Shipyard closes that loop: pick parts, see the assembled ship, decide
before printing.

**Status:** early. M1 (library scan, catalog, mesh pipeline, single-part viewer) and
M2 (mount picking, mirroring, repeated authoring, and sidecar persistence) are complete.
See the [milestones](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/milestones).

## Requirements

| | version | why |
|---|---|---|
| **JDK** | **25** | Canonical. Not optional - see below. |
| Clojure CLI | 1.12.4.1618+ | |
| Node | 24+ | shadow-cljs and the npm-managed frontend dependencies |

**Java 25 is the canonical JDK for this project.** It is not a floor to be negotiated
down. The `:run` and `:test` aliases pass `--sun-misc-unsafe-memory-access=allow`, which
does not exist before JDK 23 - an older JVM refuses to start rather than ignoring it -
and `--enable-native-access=ALL-UNNAMED`, which LWJGL needs to load its native
meshoptimizer bindings without warnings. CI pins 25 on every job and the uberjar declares
`Enable-Native-Access` in its manifest. Develop on 25.

## Quick start

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

## Development

Anything that touches the mesh pipeline needs **a platform alias for the LWJGL natives** -
`:natives-linux` below; substitute `:natives-windows`, `:natives-macos` or
`:natives-macos-arm64`. Commands without one below do not need one.

```bash
mkdir -p resources/public/js && cp node_modules/htmx.org/dist/htmx.min.js resources/public/js/   # once per clone - see below
clojure -M:natives-linux:run            # server, no jar
npx shadow-cljs watch viewport          # hot-reloaded CLJS, in a second terminal
clojure -M:dev:natives-linux -m nrepl.cmdline  # REPL; then (go), (reset), or (halt)

clojure -M:test:natives-linux                       # all suites
clojure -M:test --focus :unit                       # pure functions only, sub-second
clojure -M:test:natives-linux --focus :integration  # filesystem, natives, HTTP
npx shadow-cljs compile viewport                    # e2e needs the bundle, with test hooks
clojure -M:test:natives-linux --focus :e2e          # headless browser
npx shadow-cljs compile test && node target/js/node-tests.js  # CLJS node unit tests

clojure -M:natives-linux:canary         # data-quality probe over the real library
clojure -M:m2-human-navy-cruiser-proof --root /tmp/human-navy-cruiser-copy --out m2-human-navy-cruiser-proof.edn
clojure -M:natives-linux:benchmark --root /path/to/models --machine "CPU; RAM; GPU; storage"
clojure -M:cljfmt check src test dev build.clj    # `fix` to apply
clojure -M:clj-kondo --lint src --lint test --lint dev --lint build.clj
clojure -M:outdated                     # dependency freshness, deps.edn + package.json
```

The M2 Human Navy Cruiser proof is an on-demand check over proprietary user-owned STL
data, so it is not a CI gate and it should not run against your only copy. Materialize a
temporary Shipyard-style Cruiser library first; the proof command writes `shipyard.edn`
sidecars into that copy, reloads a fresh catalog, and writes an EDN report. See
[docs/M2_HUMAN_NAVY_CRUISER_PROOF.md](docs/M2_HUMAN_NAVY_CRUISER_PROOF.md).

The benchmark is the on-demand, machine-labelled measurement behind TECHNICAL.md §11;
it is deliberately not a CI gate. It creates fresh scan indexes and mesh caches under
the system temp directory, drives a hardware Chromium window for ten seconds, runs the
whole-library canary with four threads, and writes `benchmark.edn`. Build the dev viewport
bundle first (`npx shadow-cljs compile viewport`). Use `--viewport-mode swiftshader` only
for a CPU-renderer comparison; it does not measure the hardware viewport budget.

**htmx is copied, not bundled** - `clojure -T:build uber` does it for you, but the dev
commands above do not, so a fresh clone needs it once. The `mkdir` is not decoration:
`resources/public/js/` is gitignored build output, so it does not exist until something
creates it. Skip it and the page still renders
and the canvas still loads; the library panel simply never populates, which reads exactly
like a server bug. Why it is delivered separately from the CLJS bundle: TECHNICAL.md §8.

`deps.edn` declares `org.lwjgl/lwjgl` and `org.lwjgl/lwjgl-meshoptimizer`, which are the
Java API jars; the `.so`, `.dll` and `.dylib` are separate Maven artifacts carrying a
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

Tests come in three levels separated by **what they are allowed to touch**, not by size:
unit touches nothing outside the process, integration gets the filesystem and natives,
e2e drives a real browser. See TECHNICAL.md §10.

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
suite is hermetic: its library, mesh cache and scan index all live in a temp directory, so
it never touches your real one.

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
| [docs/M2_HUMAN_NAVY_CRUISER_PROOF.md](docs/M2_HUMAN_NAVY_CRUISER_PROOF.md) | M2 proof notes from the real Human Navy Cruiser target. |
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

Not yet chosen. The STL models this operates on are third-party purchased assets and are
not part of this repository.
