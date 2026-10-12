#!/usr/bin/env node

// Generate a Kaocha config for one CI shard. Keep discovery automatic so a new
// *_test.clj file is covered without editing a hand-maintained shard list.
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const [indexArg, countArg, output] = process.argv.slice(2);
const index = Number(indexArg);
const count = Number(countArg);
if (!Number.isInteger(index) || !Number.isInteger(count) || count < 1 || index < 0 || index >= count || !output) {
  throw new Error('usage: node shard-e2e.mjs SHARD_INDEX SHARD_COUNT OUTPUT_EDN');
}

const directory = 'test/clj/shipyard/e2e';
// Measured namespace seconds from successful CI run 2554. New files still
// participate immediately, using a conservative fixture-count estimate.
const durations = JSON.parse(readFileSync('.forgejo/scripts/e2e-durations.json', 'utf8'));
const tests = readdirSync(directory)
  .filter((name) => name.endsWith('_test.clj'))
  .map((name) => {
    const source = readFileSync(join(directory, name), 'utf8');
    const namespace = `shipyard.e2e.${name.slice(0, -4).replaceAll('_', '-')}`;
    const declared = source.match(/^\(ns\s+([^\s)]+)/m)?.[1];
    if (declared !== namespace) throw new Error(`${name}: expected namespace ${namespace}, found ${declared}`);
    const testCount = (source.match(/^\(deftest\s/gm) || []).length;
    if (!testCount) throw new Error(`${name}: no deftest forms found`);
    const weight = durations[namespace] ?? Math.max(10, testCount * 15 + source.split('\n').length * 0.025);
    return { namespace, weight };
  })
  .sort((a, b) => b.weight - a.weight || a.namespace.localeCompare(b.namespace));

if (tests.length < count) throw new Error(`only ${tests.length} E2E test files for ${count} shards`);
const shards = Array.from({ length: count }, () => ({ weight: 0, namespaces: [] }));
for (const test of tests) {
  const shard = shards.reduce((best, candidate) => candidate.weight < best.weight ? candidate : best);
  shard.namespaces.push(test.namespace);
  shard.weight += test.weight;
}

const selected = shards[index].namespaces.sort();
const patterns = selected.map((namespace) => `"^${namespace.replaceAll('.', '[.]')}$"`).join(' ');
const config = `#kaocha/v1\n{:tests [{:id :e2e\n          :test-paths ["${directory}"]\n          :ns-patterns [${patterns}]}]}\n`;
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, config);
console.log(`E2E shard ${index + 1}/${count}: ${selected.length} files, estimated ${Math.round(shards[index].weight)}s`);
for (const namespace of selected) console.log(`  ${namespace}`);
