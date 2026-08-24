# Working on Shipyard

How to *work on* this repository: reaching the forge, arranging branches, and
reading CI. What Shipyard is and why is in [SPEC.md](SPEC.md); how it is built is in
[TECHNICAL.md](TECHNICAL.md); how to build, run and test it is in [README.md](README.md).
Nothing here repeats those.

## The forge

Self-hosted Forgejo at `https://forgejo.tail943578.ts.net`, repo `MeltzgSoft/shipyard`,
reachable **only over the tailnet** - off it, every command below fails at the network,
not at auth. The git remote is `ssh://git@forgejo.tail943578.ts.net/MeltzgSoft/shipyard.git`.

## Credentials

Neither of these is in the repository, and neither should be.

- **Push** uses SSH. Check what the agent already offers with
  `ssh -T git@forgejo.tail943578.ts.net`; it names the key it authenticated with. Where
  the forge key is not the default, point at it explicitly:
  `GIT_SSH_COMMAND="ssh -i ~/.ssh/forgejo_ed25519" git push`.
- **API reads** on this repository work anonymously. Writes need a token - either the one
  `tea` holds under `~/.config/tea/`, or one at `~/.forgejo-creds`, passed as
  `curl -H "Authorization: token $(cat ~/.forgejo-creds)"`.

## tea, and when to drop to the API

`tea` works against this forge - `tea issue ls`, `tea pr ls`, `tea pr <n>`, `tea whoami`
all verified against tea 0.15.1 on 2026-08-24. An earlier session found it segfaulting in
a nil version compare inside go-version, with a dead token besides, so treat it as the
convenience path, not the guaranteed one.

The fallback is the REST API, `https://forgejo.tail943578.ts.net/api/v1/repos/MeltzgSoft/shipyard/...`
- `pulls/{n}` carries `mergeable`, `base.ref` and `head.ref`, which is what you need to
see the shape of a stack.

## Actions

**Listing runs is API.** `GET .../actions/tasks?limit=N` and `GET .../actions/runs` both
return runs with `status`, anonymously.

**Reading a log is not.** There is no per-run, per-job or log endpoint - `actions/runs/{n}`,
`actions/runs/{n}/jobs` and `actions/jobs/{id}/logs` all 404. Logs come from the endpoint
the web UI calls for itself:

```bash
curl -s -X POST \
  "https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/actions/runs/{run}/jobs/{index}/attempt/1" \
  -H "Accept: application/json" -H "Content-Type: application/json" \
  -d '{"logCursors":[{"step":N,"cursor":null,"expanded":true}]}'
```

`{run}` is the run *number*, not its id. `{index}` is the job's position in the workflow
file, not its id - in `test.yml`, 0 is `test-linux`, 1 is `test-windows`, 2 is `test-cljs`.
Post `{"logCursors":[]}` first: that returns `state.currentJob.steps` with each step's
status, which is how you find the `N` worth expanding. Lines come back at
`logs.stepsLog[].lines[].message`.

**There is no rerun API.** `runs/{n}/rerun`, `jobs/{i}/rerun` and `actions/jobs/{id}/rerun`
all 404. A job red on infrastructure rather than on the code - #30 hit
`SocketException: Network is unreachable` inside Aether - is re-run from the web UI, or by
pushing to the branch.

## Branches and stacked pull requests

One branch per issue, `feat/<issue>-<slug>`.

Work that **depends** on unmerged work branches from that branch, not from `main`, and its
pull request bases on it too; say which in the body. Independent work branches from `main`.
#29 through #32 are a worked stack: `feat/15-http` <- `feat/16-viewport` <- `feat/17-ci` <-
`feat/18-canary`.

**A fix goes in on the branch that owns the bug**, not on whichever branch happened to
surface it - in that stack, a Windows bug found on #31 was fixed on #29. A Windows-only
failure is still #29's bug if #29 wrote the code.

**The stack is kept up to date by rebasing, not by merging forward.** When `main` moves,
rebase `feat/<first>` onto it and then each branch onto its rebuilt parent, so the chain
stays linear and each pull request's diff shows only its own work. The same applies after
a fix lands on an earlier branch: rebase its descendants onto the new tip.

```bash
git update-ref refs/backup/feat/15-http origin/feat/15-http   # do this first, every time
git rebase --onto origin/main <old-main> feat/15-http
git rebase --onto feat/15-http <old-15-tip> feat/16-viewport   # ...and so on up the chain
git push --force-with-lease origin feat/16-viewport:feat/16-viewport
```

Three things this costs, all of them manageable and none of them a surprise:

- **Every rebased branch needs a force-push**, which re-runs its CI and can orphan review
  comments anchored to the old commits. Use `--force-with-lease`, never `--force`.
- **Back up first.** `git update-ref refs/backup/<branch> origin/<branch>` before touching
  anything. A wrong `--onto` upstream silently drops commits rather than conflicting - pass
  the *old tip of the parent branch*, not a merge commit's parent, or you can rebase a
  branch into being empty and the only sign is a suspiciously short `git log`.
- **Conflict resolution decides which fix survives**, so read both sides. Taking the
  incoming side wholesale is how a merged fix gets quietly reverted by a branch that
  predates it.

Afterwards, check the rebase preserved the work rather than assuming it did:
`git diff refs/backup/<branch> <branch> -- src test` should show nothing but what the new
base contributed.

## Pull requests

The description follows `.forgejo/PULL_REQUEST_TEMPLATE.md` and **CI checks it** - sections
present, "What this changes" non-empty, checklists engaged with. It checks engagement, not
prose. Run `.forgejo/scripts/check-pr-description.sh my-description.md` before pushing.

Install the pre-commit hook once per clone - git does not do it for you:
`git config core.hooksPath .githooks`. README.md has both in full.
