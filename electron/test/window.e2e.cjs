const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const {once} = require('node:events');
const {spawn} = require('node:child_process');
const {_electron} = require('playwright');
const {modelLibrary, PART_ID} = require('./fixtures.cjs');
const ELECTRON_ROOT = path.resolve(__dirname, '..');
const configured = process.env.SHIPYARD_TEST_ELECTRON;
const executablePath = configured ? path.resolve(ELECTRON_ROOT, configured) : require('electron');
// Chromium cannot start as root in the CI container with its OS sandbox.
// This flag is confined to the test harness; normal installed launches keep it.
const args = [...(process.getuid?.() === 0 ? ['--no-sandbox'] : []), ...(configured ? [] : [ELECTRON_ROOT])];
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
async function waitFor(check, message) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await check()) return;
    await sleep(100);
  }
  assert.fail(message);
}

test('real window owns a dynamic backend, is sandboxed, focuses a second launch, and persists on quit',
  {timeout: 240000}, async () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shipyard-electron-window-'));
    const models = path.join(directory, 'models');
    modelLibrary(models);
    const env = {...process.env, XDG_CONFIG_HOME: path.join(directory, 'config'),
      XDG_CACHE_HOME: path.join(directory, 'cache'), XDG_DATA_HOME: path.join(directory, 'data')};
    // Ensure Electron is launched as an app, even when a parent environment uses Node mode.
    delete env.ELECTRON_RUN_AS_NODE;
    const foreign = http.createServer((_, response) => { response.writeHead(200); response.end('foreign'); });
    foreign.listen(8080, '127.0.0.1');
    await once(foreign, 'listening');
    let application;
    try {
      application = await _electron.launch({executablePath, args, env, timeout: 120000});
      const page = await application.firstWindow({timeout: 120000});
      await page.waitForLoadState('domcontentloaded');
      const origin = new URL(page.url()).origin;
      assert.equal(new URL(origin).hostname, '127.0.0.1');
      assert.ok(Number(new URL(origin).port) > 8080);
      assert.deepEqual(await (await fetch(`${origin}/healthz`)).json(), {status: 'ok'});
      assert.deepEqual(await page.evaluate(() => ({require: typeof require, process: typeof process})),
        {require: 'undefined', process: 'undefined'});
      const preferences = await application.evaluate(({BrowserWindow}) => {
        const p = BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences();
        return {sandbox: p.sandbox, contextIsolation: p.contextIsolation, nodeIntegration: p.nodeIntegration};
      });
      assert.deepEqual(preferences, {sandbox: true, contextIsolation: true, nodeIntegration: false});
      assert.equal(await page.evaluate(async root => (await fetch('/settings', {method: 'POST',
        headers: {'content-type': 'application/x-www-form-urlencoded'}, body: new URLSearchParams({root})})).status, models), 204);
      await page.reload();
      assert.ok(await page.evaluate(async id => (await (await fetch('/library')).text()).includes(id), PART_ID));
      // Record OS-browser handoff without opening the test's links on the user's desktop.
      await application.evaluate(({shell}) => {
        globalThis.shipyardTestExternal = [];
        shell.openExternal = url => { globalThis.shipyardTestExternal.push(url); return Promise.resolve(); };
      });
      // A forbidden scheme cannot replace the application, open another window, or access local files.
      await page.evaluate(() => {
        const link = document.createElement('a'); link.href = 'file:///etc/passwd'; document.body.append(link); link.click();
      });
      await sleep(200);
      assert.equal(new URL(page.url()).origin, origin);
      assert.equal(application.windows().length, 1);
      assert.deepEqual(await application.evaluate(() => globalThis.shipyardTestExternal), []);
      await page.evaluate(() => {
        const link = document.createElement('a'); link.href = 'http://127.0.0.1:8080/foreign'; document.body.append(link); link.click();
      });
      await waitFor(() => application.evaluate(() => globalThis.shipyardTestExternal.length === 1),
        'ordinary web links must be handed to the external browser');
      assert.equal(new URL(page.url()).origin, origin);
      await page.evaluate(() => { window.location.href = '/healthz'; });
      await page.waitForURL(`${origin}/healthz`);
      await application.evaluate(({BrowserWindow}) => BrowserWindow.getAllWindows()[0].minimize());
      const second = spawn(executablePath, args, {env, stdio: 'pipe'});
      const [secondCode] = await once(second, 'exit');
      assert.equal(secondCode, 0);
      await waitFor(() => application.evaluate(({BrowserWindow}) => {
        const windows = BrowserWindow.getAllWindows();
        return windows.length === 1 && !windows[0].isMinimized();
      }), 'second launch must restore the original window');
      assert.equal(new URL(page.url()).origin, origin, 'second launch must retain the first backend');
      const closed = once(application.process(), 'exit');
      await application.evaluate(({BrowserWindow}) => BrowserWindow.getAllWindows()[0].close());
      const [closeCode, closeSignal] = await closed;
      assert.equal(closeCode, 0);
      assert.equal(closeSignal, null);
      application = null;
      await assert.rejects(fetch(`${origin}/healthz`), 'window close must await backend shutdown');
      application = await _electron.launch({executablePath, args, env, timeout: 120000});
      const reopened = await application.firstWindow({timeout: 120000});
      await reopened.waitForLoadState('domcontentloaded');
      assert.ok(await reopened.evaluate(async id => (await (await fetch('/library')).text()).includes(id), PART_ID),
        'folder selection survives real application restart');
      await application.close();
      application = null;
    } finally {
      if (application) await application.close();
      foreign.closeAllConnections();
      await new Promise(resolve => foreign.close(resolve));
      fs.rmSync(directory, {recursive: true, force: true});
    }
  });
