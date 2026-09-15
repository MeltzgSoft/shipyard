## What this changes


## Documentation

SPEC.md §12 makes these merge obligations, not follow-ups.

- [ ] User-facing behaviour changed -> `docs/MANUAL.md` updated in this PR
- [ ] Setup, build, run or test workflow changed -> `README.md` updated in this PR
- [ ] A milestone section in the manual still says *Not yet built* but is now built -> updated
- [ ] Neither applies

## Spec

- [ ] Implementation showed the spec was wrong -> spec changed here, and the change is described above
- [ ] No spec change needed

## Checks

- [ ] Behavior changed -> E2E tests added or updated in this PR (SPEC §12.4; describe coverage above)
- [ ] `clojure -M:test:natives-linux` passes
- [ ] `clojure -M:cljfmt check src test build.clj` passes
- [ ] `clojure -M:clj-kondo --lint src --lint test --lint build.clj` passes
