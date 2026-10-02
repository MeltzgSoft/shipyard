# Desktop runtime tests

The Electron main process is ClojureScript under `src/cljs/shipyard/desktop`.
The JVM owns the existing durable store and HTTP application. Build commands and
installation instructions are in the repository README.

After building the payload:

```sh
npx shadow-cljs compile desktop-support
npm test --prefix electron
npm run test:e2e --prefix electron
```

The Node suite starts actual processes and sockets. It checks malformed readiness,
foreign launch tokens, bounded output, launch errors, health timeouts, exact payload
selection, occupied port 8080, durable restart, and stdin/EOF shutdown. To exercise
the staged Java runtime instead of the development JVM:

```sh
SHIPYARD_TEST_RESOURCES=target/desktop/resources npm test --prefix electron
```

`SHIPYARD_TEST_RESOURCES` resolves relative to the repository root. The window suite
runs actual Electron through Playwright and uses an isolated XDG data profile. It
checks the owned dynamic origin, renderer isolation, navigation restrictions,
second-instance focus, and persistence and backend exit on application shutdown.
External-browser handoff is recorded in the harness so test links do not open the
user's browser. To exercise the Linux unpacked app:

```sh
SHIPYARD_TEST_ELECTRON=dist/linux-unpacked/shipyard npm run test:e2e --prefix electron
```

That executable path resolves relative to `electron/`. GitHub Linux CI runs the
window suite as an ordinary user under `xvfb-run` against the actual installed
Debian package at `/opt/Shipyard/shipyard`. This exercises the installer's
sandbox-helper permissions and Ubuntu AppArmor profile, including second launch.
Playwright disables Chromium's OS sandbox by default; the harness explicitly
enables it for ordinary-user launches and checks that `--no-sandbox` is absent.
Only when the harness itself runs as root does it supply Chromium's required
`--no-sandbox`; production launch never adds that flag. The suite also verifies
`sandbox: true`, context isolation, and disabled renderer Node integration.

## Hosted packaging and releases

`.github/workflows/desktop.yml` follows Dapr's shared-payload/native-packaging
pattern: build the jar and CLJS shell once, then call `package-desktop` on
GitHub-hosted Linux, Windows and Apple Silicon macOS. The shared artifact also
carries the compiled protocol-test library, which is excluded from installed
app files. The native matrix tests each bundled Java runtime and uploads its
installers. Linux also runs this packaged-window suite.

Branch builds and manual `installers_only` smoke runs publish nothing. Version
tags attach the complete installer set and checksums to a GitHub Release after
all matrix legs pass. Ordinary JVM, browser and lint CI stays on Forgejo.
