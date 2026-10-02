const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const http = require('node:http');
const {once} = require('node:events');
const {startBackend, spawnBackend} = require('../compiled/backend-tests.js');
const {modelLibrary, PART_ID} = require('./fixtures.cjs');
const REPO_ROOT = path.resolve(__dirname, '../..');
const TOKEN = crypto.randomUUID();

// Actual child HTTP server and pipe protocol, rather than replacing spawn/fetch.
const fixture = String.raw`
const http = require('node:http');
const mode = process.argv[1], token = process.argv[2];
if (mode === 'exit') process.exit(42);
if (mode === 'too-long') process.stdout.write('x'.repeat(70000));
const server = http.createServer((req, res) => {
  process.stdout.write('PROBED\n');
  if (mode !== 'hang-health') { res.writeHead(200); res.end('{"status":"ok"}'); }
});
server.listen(0, '127.0.0.1', () => {
  const port = server.address().port;
  if (mode !== 'never' && mode !== 'too-long')
    process.stdout.write('SHIPYARD_DESKTOP_READY ' + (mode === 'wrong-token' ? 'foreign' : token) + ' ' +
      (mode === 'bad-port' ? 65536 : port) + '\n');
});
let stopped = false;
function stop() { if (stopped) return; stopped = true; server.closeAllConnections(); server.close(() => process.exit(0)); }
process.stdin.on('data', data => { if (String(data).includes('SHIPYARD_DESKTOP_STOP')) stop(); });
process.stdin.on('end', stop);
`;

function child(mode, options = {}) {
  return spawnBackend({command: process.execPath, args: ['-e', fixture, mode, TOKEN], token: TOKEN,
    'startup-ms': 1000, 'shutdown-ms': 2000, ...options});
}

for (const [mode, expected] of [
  ['wrong-token', /timed out/], ['bad-port', /Invalid owned/], ['too-long', /protocol limit/],
  ['exit', /exited before readiness/], ['never', /timed out/], ['hang-health', /timed out/]
]) {
  test(`owned child rejects ${mode} and shuts down`, {timeout: 15000}, async () => {
    const backend = child(mode, {'startup-ms': 500});
    let stdout = '';
    backend.child.stdout.on('data', chunk => stdout += chunk);
    try {
      await assert.rejects(backend.ready, expected);
      if (mode === 'wrong-token') assert.doesNotMatch(stdout, /PROBED/, 'foreign nonce must never trigger health polling');
    } finally {
      const status = await backend.stop();
      assert.equal(status.signal, null, 'private-pipe shutdown must not need a kill');
    }
  });
}

test('owned actual child reports dynamic port and stops once', {timeout: 15000}, async () => {
  const backend = child('ok');
  try {
    const ready = await backend.ready;
    assert.match(ready.url, /^http:\/\/127\.0\.0\.1:\d+$/);
    assert.equal(new URL(ready.url).port, String(ready.port));
    assert.equal((await fetch(`${ready.url}/healthz`)).status, 200);
    const first = backend.stop(), second = backend.stop();
    assert.equal(first, second);
    assert.equal((await first).code, 0);
    await assert.rejects(fetch(`${ready.url}/healthz`));
  } finally { await backend.stop(); }
});

test('launch error rejects without hanging shutdown', {timeout: 10000}, async () => {
  const backend = spawnBackend({command: path.join(os.tmpdir(), `missing-java-${crypto.randomUUID()}`),
    args: [], token: TOKEN, 'startup-ms': 500, 'shutdown-ms': 500});
  await assert.rejects(backend.ready, /Could not launch/);
  assert.match((await backend.stop()).error, /ENOENT/);
});

test('missing packaged payload and exact development JAR fail without fallback', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shipyard-payload-'));
  try {
    const opts = {'packaged?': true, 'resources-path': directory, platform: process.platform};
    assert.throws(() => startBackend(opts), /Missing Shipyard JAR/);
    fs.writeFileSync(path.join(directory, 'shipyard.jar'), 'fixture');
    assert.throws(() => startBackend({...opts, java: process.execPath}), /Missing bundled Java runtime/);
    fs.mkdirSync(path.join(directory, 'electron'));
    fs.mkdirSync(path.join(directory, 'target'));
    fs.writeFileSync(path.join(directory, 'target', 'shipyard-newer.jar'), 'fixture');
    assert.throws(() => startBackend({'packaged?': false, 'app-path': path.join(directory, 'electron')}),
      /shipyard-0\.1\.0-SNAPSHOT\.jar/);
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});

function isolatedEnvironment(directory) {
  return {XDG_CONFIG_HOME: path.join(directory, 'config'), XDG_DATA_HOME: path.join(directory, 'data'),
    XDG_CACHE_HOME: path.join(directory, 'cache')};
}
function production(directory) {
  const resources = process.env.SHIPYARD_TEST_RESOURCES;
  return startBackend({'packaged?': Boolean(resources),
    'resources-path': resources && path.resolve(REPO_ROOT, resources),
    'app-path': path.join(REPO_ROOT, 'electron'), platform: process.platform,
    env: isolatedEnvironment(directory), 'startup-ms': 120000, 'shutdown-ms': 60000});
}

test('real JVM selects an owned port, flushes settings, and exits on pipe EOF', {timeout: 240000}, async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shipyard-desktop-jvm-'));
  const models = path.join(directory, 'models');
  modelLibrary(models);
  const foreign = http.createServer((_, response) => { response.writeHead(200); response.end('foreign'); });
  foreign.listen(8080, '127.0.0.1');
  await once(foreign, 'listening');
  let backend;
  try {
    backend = production(directory);
    const ready = await backend.ready;
    assert.ok(ready.port > 8080, '8080 is owned by another service');
    assert.deepEqual(await (await fetch(`${ready.url}/healthz`)).json(), {status: 'ok'});
    const saved = await fetch(`${ready.url}/settings`, {method: 'POST',
      headers: {'content-type': 'application/x-www-form-urlencoded'}, body: new URLSearchParams({root: models})});
    assert.equal(saved.status, 204);
    assert.equal((await backend.stop()).code, 0);
    backend = production(directory);
    const reopened = await backend.ready;
    assert.ok((await (await fetch(`${reopened.url}/library`)).text()).includes(PART_ID), 'selected folder survives process restart');
    const exit = once(backend.child, 'exit');
    backend.child.stdin.end(); // A disappeared parent closes exactly this pipe.
    const [code, signal] = await exit;
    assert.equal(code, 0);
    assert.equal(signal, null);
    await assert.rejects(fetch(`${reopened.url}/healthz`));
  } finally {
    if (backend) await backend.stop();
    foreign.closeAllConnections();
    await new Promise(resolve => foreign.close(resolve));
    fs.rmSync(directory, {recursive: true, force: true});
  }
});
