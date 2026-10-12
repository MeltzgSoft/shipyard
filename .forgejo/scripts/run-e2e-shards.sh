#!/usr/bin/env bash
set -euo pipefail

# Separate JVMs keep Playwright, Jetty, and Datalevin fixtures isolated while
# the entire E2E check occupies one Forgejo runner slot.
shard_count=4
shard_output=target/e2e-shards
temp_root="${SHIPYARD_E2E_TMPDIR:-/shipyard-test-tmp}"
mkdir -p "$shard_output"

# Resolve the classpath once before concurrent JVMs touch the local Maven and
# Clojure CLI caches. The browser-install step used :test without natives.
clojure -Spath -M:test:natives-linux > /dev/null

pids=()
for ((shard=0; shard<shard_count; shard++)); do
  node .forgejo/scripts/shard-e2e.mjs "$shard" "$shard_count" "$shard_output/$shard.edn"
  mkdir -p "$temp_root/shard-$shard"
done

for ((shard=0; shard<shard_count; shard++)); do
  (
    set -o pipefail
    clojure "-J-Djava.io.tmpdir=$temp_root/shard-$shard" -M:test:natives-linux \
      --config-file "$shard_output/$shard.edn" --focus :e2e \
      --reporter kaocha.report/documentation 2>&1 | sed -u "s/^/[e2e $shard] /"
  ) &
  pids+=("$!")
done

failed=0
for ((shard=0; shard<shard_count; shard++)); do
  if ! wait "${pids[$shard]}"; then
    echo "E2E shard $shard failed" >&2
    failed=1
  fi
done
exit "$failed"
