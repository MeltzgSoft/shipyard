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

That executable path resolves relative to `electron/`. Linux CI runs the window
suite under `xvfb-run`. When the harness itself runs as root, it supplies Chromium's
required `--no-sandbox`; production launch never adds that flag. The local window
suite also runs as an ordinary user with the default OS sandbox. Both verify
`sandbox: true`, context isolation, and disabled renderer Node integration.
