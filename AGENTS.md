# Agent entry point

Read [CLAUDE.md](CLAUDE.md) for repository workflow, forge access, branch ownership
and PR requirements. Use [README.md](README.md) for build/test commands,
[SPEC.md](SPEC.md) for intended behavior, [TECHNICAL.md](TECHNICAL.md) for architecture,
and [docs/MANUAL.md](docs/MANUAL.md) for supported user workflows.

## Work tracking

Read the relevant issue and milestone before changing behavior. Keep progress,
implementation gaps and handoff decisions in Forgejo, not in specifications or this file.

- [M4 milestone](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/milestone/4)
- [M4 clean-start handoff and branch provenance (#117)](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/issues/117)
- [Store (#106)](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/issues/106),
  [operations (#107)](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/issues/107),
  [workspace ownership and Ship Browser (#108)](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/issues/108)

## Local skills

Check `~/.agents/skills/` as well as the session's advertised skills. Read applicable
`SKILL.md` files completely before acting. Relevant local skills include
`clojure-best-practices`, `clojure-new-feature`, `pure-logic-boundaries`,
`server-rendered-htmx`, `browser-e2e-testing` and `project-documentation`.
If a skill is unavailable, say so and follow the repository's documented contracts.
Project-specific architecture takes precedence over generic examples: Shipyard's
durable store is Datalevin; a skill's SQL examples do not authorize replacing it
or bypassing the shared entity and transaction contracts.
