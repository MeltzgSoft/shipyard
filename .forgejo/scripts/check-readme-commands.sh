#!/usr/bin/env bash
# Run the commands README's Development section actually prints.
#
# Usage: check-readme-commands.sh [readme-path]
#
# **The commands are extracted from README, never copied into this script.**
# Copying them would recreate the bug this exists to prevent: two lists that
# have to agree, where only one of them is ever executed. That is precisely how
# #37 and #39 survived - the workflows spelled every command correctly, CI
# stayed green, and nothing ran what the README told a person to run.
#
# What cannot run is skipped by name, with a reason, printed. A silent skip is
# indistinguishable from a pass, and this script exists because things that look
# like passes were not.

set -uo pipefail

README="${1:-README.md}"
[[ -f "$README" ]] || { echo "::error::no such file: $README"; exit 2; }

failures=()
skipped=()

# --- the commands, straight out of the file ---------------------------------
# The first fenced bash block after the "## Development" heading. Comments and
# blank lines go; everything else is a command somebody is being told to run.
commands=$(awk '
  /^## Development/     { in_section = 1; next }
  in_section && /^```/  { if (in_block) exit; in_block = 1; next }
  in_block              { print }
' "$README" | sed 's/[[:space:]]*#.*$//' | grep -vE '^[[:space:]]*$')

[[ -n "$commands" ]] || { echo "::error::found no commands under '## Development' in $README"; exit 2; }

echo "Commands found in $README:"
while IFS= read -r c; do echo "    $c"; done <<< "$commands"
echo

skip() { skipped+=("$1 -- $2"); echo "SKIP  $1"; echo "      $2"; }

run() {
  echo "RUN   $1"
  if eval "$1" > /tmp/readme-cmd.log 2>&1; then
    echo "      ok"
  else
    echo "      FAILED (exit $?)"
    tail -30 /tmp/readme-cmd.log | sed 's/^/      | /'
    failures+=("$1")
  fi
}

# --- a library to point things at -------------------------------------------
# There is no default library any more (#35), so the commands that read one need
# somewhere to look. This also exercises the settings file the UI writes.
fixture="$(mktemp -d)/library"
mkdir -p "$fixture/Test Bundle/Cruiser/Cube"
cp test/fixtures/cube-ascii-crlf.stl "$fixture/Test Bundle/Cruiser/Cube/unsupported.stl"
export XDG_CONFIG_HOME="$(mktemp -d)"
export XDG_CACHE_HOME="$(mktemp -d)"
mkdir -p "$XDG_CONFIG_HOME/shipyard"
printf '{:root "%s"}\n' "$fixture" > "$XDG_CONFIG_HOME/shipyard/library.edn"

# --- the server, which is the one that fails quietly -------------------------
# Starting is not the assertion. Both natives bugs let the process start, answer
# /healthz and list the library; #37 broke only the panel that loads it and #39
# broke only the part that renders. So: ask for a page, ask for htmx, and ask
# for geometry.
smoke_test_server() {
  local cmd="$1" port=8123 pid
  echo "RUN   $cmd  (backgrounded, then smoke-tested)"
  PORT=$port $cmd > /tmp/readme-server.log 2>&1 &
  pid=$!
  # shellcheck disable=SC2064
  trap "kill $pid 2>/dev/null || true" RETURN

  for _ in $(seq 1 90); do
    curl -fsS "http://127.0.0.1:$port/healthz" >/dev/null 2>&1 && break
    sleep 1
  done

  local part="Test%20Bundle/Cruiser/Cube"
  local problems=()
  curl -fsS "http://127.0.0.1:$port/healthz"        >/dev/null 2>&1 || problems+=("/healthz did not answer")
  curl -fsS "http://127.0.0.1:$port/"               >/dev/null 2>&1 || problems+=("/ did not answer")
  # #37: the page renders without this and the library silently never loads.
  curl -fsS "http://127.0.0.1:$port/js/htmx.min.js" >/dev/null 2>&1 || problems+=("/js/htmx.min.js 404 - the htmx copy step is missing from README")
  curl -fsS "http://127.0.0.1:$port/library" 2>/dev/null | grep -q "Cube" || problems+=("/library did not list the fixture part")

  # #39: everything above passes without the natives. Only geometry fails.
  curl -fsS "http://127.0.0.1:$port/part/$part" >/dev/null 2>&1
  local ready=""
  for _ in $(seq 1 90); do
    if curl -fsS -D - -o /dev/null "http://127.0.0.1:$port/part/$part" 2>/dev/null | grep -q "load-mesh"; then
      ready=yes; break
    fi
    curl -fsS "http://127.0.0.1:$port/part/$part" 2>/dev/null | grep -q "could not prepare" && break
    sleep 1
  done
  [[ -n "$ready" ]] || problems+=("the part never rendered - the natives alias is missing from the run command")

  if (( ${#problems[@]} )); then
    echo "      FAILED"
    for p in "${problems[@]}"; do echo "      | $p"; done
    tail -20 /tmp/readme-server.log | sed 's/^/      | /'
    failures+=("$cmd")
  else
    echo "      ok - served the shell, htmx, the library and a rendered part"
  fi
}

# --- the canary, which fails by lying ---------------------------------------
# Without the natives it does not crash: canary.clj catches per-part exceptions
# on purpose, so it reports every part in the library as a finding. A probe
# whose job is naming broken models instead names all of them.
smoke_test_canary() {
  local cmd="$1"
  echo "RUN   $cmd  (against the fixture library)"
  if $cmd --root "$fixture" --out /tmp/readme-canary.edn > /tmp/readme-canary.log 2>&1 \
     && ! grep -q "liblwjgl" /tmp/readme-canary.log; then
    echo "      ok - clean report"
  else
    echo "      FAILED"
    grep -i "liblwjgl\|error" /tmp/readme-canary.log | head -5 | sed 's/^/      | /'
    failures+=("$cmd")
  fi
}

# --- dispatch ----------------------------------------------------------------
while IFS= read -r cmd; do
  [[ -z "$cmd" ]] && continue
  case "$cmd" in
    *"shadow-cljs watch"*)
      skip "$cmd" "a watcher never exits; the compile it wraps is covered below" ;;
    *"nrepl.cmdline"*)
      skip "$cmd" "an interactive REPL never exits; its namespaces are covered by linting" ;;
    *":outdated"*)
      skip "$cmd" "reaches the network and reports newer releases by design, so it is never deterministically green" ;;
    *":benchmark"*)
      skip "$cmd" "requires the real library, a labelled machine and a hardware GPU; it is deliberately outside CI" ;;
    *":m2-human-navy-cruiser-proof"*)
      skip "$cmd" "requires the proprietary Human Navy Cruiser library copy; it is deliberately outside CI" ;;
    *"--focus :e2e"*)
      skip "$cmd" "has its own job (test-e2e), which owns the browser setup" ;;
    *"--focus :integration"*)
      skip "$cmd" "has its own job (test-linux); running it twice doubles CI for no new signal" ;;
    *":test:natives-linux")
      # No --focus: "all suites" includes :e2e, so this needs the browser that
      # test-e2e sets up. Its unit and integration levels are test-linux's.
      skip "$cmd" "all suites includes :e2e, which needs the browser owned by test-e2e; its other levels are test-linux's" ;;
    *":canary"*)
      smoke_test_canary "$cmd" ;;
    *":run"*)
      smoke_test_server "$cmd" ;;
    *)
      run "$cmd" ;;
  esac
done <<< "$commands"

# --- report ------------------------------------------------------------------
echo
if (( ${#skipped[@]} )); then
  echo "Skipped ${#skipped[@]} command(s):"
  for s in "${skipped[@]}"; do echo "  - $s"; done
  echo
fi

if (( ${#failures[@]} )); then
  echo "::error::${#failures[@]} command(s) from README's Development section do not work as written."
  for f in "${failures[@]}"; do echo "  - $f"; done
  echo
  echo "Either fix the command in README.md, or fix what it is describing."
  exit 1
fi

echo "Every runnable command in README's Development section works as written."
