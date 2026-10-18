// Offline browser checks. Set SLOT_PLAYWRIGHT to an installed Playwright module when needed.
const { chromium } = require(process.env.SLOT_PLAYWRIGHT || 'playwright');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');

const web = path.resolve(__dirname, '../web');
const bundled = path.resolve(__dirname, '../../../app/src/main/assets/studio');
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAD0lEQVR4nGO0snJiYGAAAAOUALgNWObMAAAAAElFTkSuQmCC', 'base64');
const state = { pin: 'K7M2QD', names: ['Alpha', 'Beta'], labelledNames: null, boxedNames: null, shells: 'Alpha = auto 123456 solid\nBeta = auto 654321 solid\n', version: 1, writes: [], failSave: false, failDelete: false, delay: 0, labelled: true, art: true };
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
  const reply = (status, body, type = 'application/json') => { res.writeHead(status, { 'Content-Type': type }); res.end(typeof body === 'object' && !Buffer.isBuffer(body) ? JSON.stringify(body) : body); };
  if (parts[0] === 'api') {
    if (req.headers.authorization !== `Bearer ${state.pin}`) return reply(401, { error: 'pin' });
    const hasLabel = (stem) => state.labelledNames ? state.labelledNames.includes(stem) : state.labelled;
    const hasBox = (stem) => state.boxedNames ? state.boxedNames.includes(stem) : hasLabel(stem);
    if (parts[1] === 'library') return reply(200, {
      carts: state.names.map((stem) => ({ platform: 'GBA', stem, file: `${stem}.gba`, size: 512 })),
      labels: state.names.filter(hasLabel).map((stem) => ({ platform: 'GBA', stem })),
      boxarts: state.names.filter(hasBox).map((stem) => ({ platform: 'GBA', stem })),
    });
    if (req.method === 'GET' && parts[1] === 'shells') return reply(200, { system: '', labels: state.shells, version: String(state.version) });
    if (req.method === 'GET' && ['labels', 'boxart'].includes(parts[1])) return reply(200, png, 'image/png');
    if (req.method === 'GET' && parts[4] === 'head') return reply(200, Buffer.alloc(0x150), 'application/octet-stream');
    if (req.method === 'GET' && parts[4] === 'crc') return reply(200, { crc: state.crcs?.[parts[3]] ?? 1 });
    if (req.method === 'PUT' && parts[1] === 'import') {
      for await (const _ of req) { /* drain */ }
      const stem = parts[3].slice(0, -4);
      if (!state.names.includes(stem)) state.names.push(stem);
      return reply(201, { result: 'added', file: parts[3] });
    }
    if (req.method === 'PUT') {
      let body = '';
      for await (const bytes of req) body += bytes;
      if (state.delay) await new Promise((r) => setTimeout(r, state.delay));
      if (state.failSave) return reply(507, { error: 'storage' });
      state.writes.push([url.pathname, body]);
      if (parts[1] === 'shells') { state.shells = body; return reply(200, { version: String(++state.version) }); }
      return reply(200, { result: 'written' });
    }
    if (req.method === 'DELETE' && ['labels', 'boxart'].includes(parts[1])) {
      state.writes.push([`DELETE ${url.pathname}`, '']);
      return reply(200, { result: 'deleted' });
    }
    if (req.method === 'DELETE' && parts[1] === 'games') {
      if (state.delay) await new Promise((r) => setTimeout(r, state.delay));
      if (state.failDelete) return reply(500, { error: 'io' });
      const stem = parts[3].slice(0, -4);
      state.names = state.names.filter((name) => name !== stem);
      state.shells = state.shells.split('\n').filter((line) => !line.startsWith(`${stem} =`)).join('\n');
      state.version++;
      return reply(200, { result: 'deleted' });
    }
    return reply(404, { error: 'not_found' });
  }
  if (parts[0] === 'art') return parts.at(-1) === 'index.json'
    ? reply(200, state.art ? { '00000001': { wheel: 'GBA/wheel/00000001.png', 'support-texture': 'GBA/support-texture/00000001.png' } } : {})
    : reply(200, png, 'image/png');
  try {
    const relative = parts.join('/') || 'index.html';
    if (parts.some((part) => part === '..')) return reply(404, '');
    const file = path.join(parts[0] === 'pkg' ? bundled : web, relative);
    reply(200, await fs.readFile(file), relative.endsWith('.wasm') ? 'application/wasm' : relative.endsWith('.js') ? 'text/javascript' : relative.endsWith('.css') ? 'text/css' : 'text/html');
  } catch { reply(404, ''); }
});

(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true,
    ...(process.env.SLOT_CHROMIUM ? { executablePath: process.env.SLOT_CHROMIUM } : {}) });
  try {
    const page = await browser.newPage({ locale: 'zh-CN', viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const dat = 'game (\n\tname "Gamma (USA)"\n\trom ( name "Gamma (USA).gba" size 512 crc 00000001 )\n)\n';
    await page.route('https://**/*', (route) => route.fulfill({
      status: !state.art && route.request().url().endsWith('.png') ? 404 : 200,
      body: route.request().url().endsWith('.dat') ? dat : state.art && route.request().url().endsWith('.png') ? png : '',
    }));
    await page.goto(`http://127.0.0.1:${server.address().port}/?pin=K7M2QD`);
    await page.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelectorAll('.cart[data-state="has-label"]').length === 2);
    assert.equal(await page.title(), 'Slot Manager');
    assert.equal(await page.locator('h1.title').textContent(), 'Slot Manager');
    assert.equal(await page.locator('.cart[data-state="has-label"] .face').first().evaluate((el) => getComputedStyle(el).opacity), '1');
    assert.equal(await page.locator('.cart[data-state="has-label"] .game').first().textContent(), '');
    assert.equal(await page.locator('#write, #ed-undo, #ed-redo, #ed-rename, #ed-manage, #ed-menu, #ed-delete').count(), 0);
    const open = async (name) => {
      await page.locator('.cart').filter({ has: page.locator('.stem', { hasText: name }) }).click();
      await page.waitForFunction(() => !document.querySelector('#ed-shell').disabled);
    };
    const colour = async (hex) => {
      await page.locator('#ed-shell').click();
      await page.locator('#ed-shell-custom').evaluate((input, value) => { input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); input.dispatchEvent(new Event('change', { bubbles: true })); }, hex);
    };
    await open('Alpha');
    // Dismissing the file picker must leave the editor open.
    await page.locator('#ed-file').evaluate((el) => el.dispatchEvent(new Event('cancel', { bubbles: true })));
    assert.equal(await page.locator('#editor').isVisible(), true);
    assert.equal(await page.locator('#ed-save').isDisabled(), true);
    assert.equal(await page.locator('#ed-revert').isDisabled(), true);
    // Game search shows a clear empty state when no English entry matches, and clicking outside closes the popover.
    await page.locator('#ed-game').click();
    await page.locator('#ed-query').fill('\u4E2D\u6587\u6E38\u620F\u540D');
    assert.equal(await page.locator('#ed-hits .hits-empty').textContent(), '\u672A\u627E\u5230\u5339\u914D\u6E38\u620F\uFF0C\u8BF7\u5C1D\u8BD5\u7528\u82F1\u6587\u540D\u79F0\u641C\u7D22');
    await page.locator('#ed-face').click();
    assert.equal(await page.locator('#ed-games').isVisible(), false);
    // Official shell presets are translated in Chinese mode.
    await page.locator('#ed-shell').click();
    await page.locator('#ed-shell-presets button[data-value="auto 249c60 clear"]').click();
    assert.equal(await page.locator('#ed-shell span').textContent(), '\u5B9D\u53EF\u68A6 \u7EFF\u5B9D\u77F3');
    await page.locator('#ed-revert').click();
    // Turning both switches off removes a saved label and cover on save.
    await page.locator('#ed-label-on').click();
    await page.locator('#ed-box-on').click();
    assert.equal(await page.locator('#ed-save').isEnabled(), true);
    await page.locator('#ed-revert').click();
    assert.equal(await page.locator('#ed-save').isDisabled(), true);
    await colour('#cc4433');
    await page.locator('#ed-close').click();
    await page.locator('#decision').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#decision-title').textContent(), '\u6709\u672A\u4FDD\u5B58\u7684\u4FEE\u6539');
    await page.locator('#decision-cancel').click();
    assert.equal(await page.locator('#editor').isVisible(), true);
    await page.locator('#ed-revert').click();
    assert.equal(await page.locator('#ed-save').isDisabled(), true);
    await colour('#cc4433');
    state.failSave = true;
    await page.locator('#ed-save').click();
    await page.waitForFunction(() => !document.querySelector('#ed-save').disabled);
    assert.match(await page.locator('#ed-note').textContent(), /\u64CD\u4F5C\u5931\u8D25/);
    assert.equal(await page.locator('#ed-revert').isEnabled(), true);
    state.failSave = false;
    state.delay = 350;
    await page.locator('#ed-save').click();
    assert.equal(await page.locator('#ed-save').isDisabled(), true);
    assert.equal(await page.locator('.ed-body').evaluate((el) => el.inert), true);
    await page.waitForFunction(() => document.querySelector('#ed-note').textContent === '\u5DF2\u4FDD\u5B58');
    if (process.env.SLOT_SCREENSHOTS) { await fs.mkdir(process.env.SLOT_SCREENSHOTS, { recursive: true }); await page.locator('#editor').screenshot({ path: path.join(process.env.SLOT_SCREENSHOTS, 'editor-saved.png') }); }
    assert.equal(state.writes.length, 1);
    assert.match(state.shells, /Alpha = auto cc4433 solid/);
    assert.match(state.shells, /Beta = auto 654321 solid/);
    await colour('#112233');
    await page.locator('#ed-revert').click();
    assert.equal(await page.locator('#ed-shell-custom').inputValue(), '#cc4433');
    await page.locator('#ed-close').click();
    await open('Beta');
    await colour('#445566');
    await page.locator('#ed-close').click();
    await page.locator('#decision-discard').click();
    await open('Beta');
    assert.equal(await page.locator('#ed-save').isDisabled(), true);
    await colour('#445566');
    // Stale pairing code during an open editor prompts in place and completes the save without losing edits.
    state.pin = 'N9P4R8';
    await page.locator('#ed-save').click();
    await page.locator('#reauth').waitFor({ state: 'visible' });
    await page.locator('#reauth-code').fill('WRONG1');
    await page.locator('#reauth-go').click();
    await page.waitForFunction(() => document.querySelector('#reauth-note').classList.contains('bad'));
    await page.locator('#reauth-code').fill('N9P4R8');
    await page.locator('#reauth-go').click();
    await page.locator('#reauth').waitFor({ state: 'hidden' });
    await page.waitForFunction(() => document.querySelector('#ed-note').textContent === '\u5DF2\u4FDD\u5B58');
    assert.match(state.shells, /Beta = auto 445566 solid/);
    await colour('#556677');
    await page.locator('#ed-close').click();
    await page.locator('#decision-confirm').click();
    await page.locator('#editor').waitFor({ state: 'hidden' });
    assert.match(state.shells, /Beta = auto 556677 solid/);

    // Shelf menus overlay the page without opening or moving the appearance editor.
    const more = (name) => page.locator('.cart').filter({ has: page.locator('.stem', { hasText: name }) }).locator('.cart-more');
    const output = process.env.SLOT_SCREENSHOTS;
    if (output) await fs.mkdir(output, { recursive: true });
    const positions = await page.locator('.cart').evaluateAll((cards) => cards.map((el) => ({ top: el.offsetTop, height: el.offsetHeight })));
    await more('Alpha').click();
    assert.equal(await page.locator('#editor').isVisible(), false);
    assert.equal(await page.locator('#game-menu').isVisible(), true);
    assert.equal(await page.locator('#game-menu').evaluate((el) => getComputedStyle(el).position), 'fixed');
    assert.deepEqual(await page.locator('.cart').evaluateAll((cards) => cards.map((el) => ({ top: el.offsetTop, height: el.offsetHeight }))), positions);
    assert.equal(await more('Alpha').getAttribute('aria-expanded'), 'true');
    await more('Beta').click();
    assert.equal(await more('Alpha').getAttribute('aria-expanded'), 'false');
    assert.equal(await more('Beta').getAttribute('aria-expanded'), 'true');
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#game-menu').isVisible(), false);
    assert.equal(await more('Beta').evaluate((el) => el === document.activeElement), true);
    await page.keyboard.press('ArrowDown');
    assert.equal(await page.locator('#game-delete').evaluate((el) => el === document.activeElement), true);
    await page.locator('h1.title').click();
    assert.equal(await page.locator('#game-menu').isVisible(), false);
    await more('Alpha').click();
    if (output) await page.screenshot({ path: path.join(output, 'studio-shelf-menu-desktop.png') });
    await page.locator('#game-delete').click();
    assert.equal(await page.locator('#game-menu').isVisible(), false);
    assert.match(await page.locator('#decision-text').textContent(), /\u5B58\u6863\u4F1A\u4FDD\u7559/);
    await page.locator('#decision-cancel').click();
    assert.deepEqual(state.names, ['Alpha', 'Beta']);
    assert.equal(await more('Alpha').evaluate((el) => el === document.activeElement), true);
    state.failDelete = true;
    await more('Alpha').click();
    await page.locator('#game-delete').click();
    await page.locator('#decision-confirm').click();
    await page.waitForFunction(() => document.querySelector('.cart[aria-busy="true"]') !== null);
    assert.equal(await more('Alpha').isDisabled(), true);
    await page.waitForFunction(() => !document.querySelector('#banner').hidden && document.querySelector('#banner').textContent.includes('\u64CD\u4F5C\u5931\u8D25'));
    assert.equal(await page.locator('#editor').isVisible(), false);
    assert.equal(await page.locator('.cart').count(), 2);
    state.failDelete = false;
    await more('Alpha').click();
    await page.locator('#game-delete').click();
    await page.locator('#decision-confirm').click();
    await page.waitForFunction(() => document.querySelectorAll('.cart').length === 1 && !document.querySelector('.cart-more').disabled);
    assert.equal(await page.locator('.cart').count(), 1);
    assert.equal(await more('Beta').evaluate((el) => el === document.activeElement), true);
    await more('Beta').click();
    await page.locator('#game-delete').click();
    await page.locator('#decision-confirm').click();
    await page.waitForFunction(() => document.querySelectorAll('.cart').length === 0 && document.querySelector('#banner').textContent.includes('\u8FD8\u6CA1\u6709\u6E38\u620F'));
    assert.equal(await page.locator('.cart').count(), 0);
    assert.match(await page.locator('#banner').textContent(), /\u8FD8\u6CA1\u6709\u6E38\u620F/);

    // Batch generation separates unlabelled count from fillable count; unsaved previews state so.
    state.names = ['Gamma', 'Delta']; state.labelled = false; state.shells = ''; state.delay = 0; state.writes = [];
    await page.reload();
    await page.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelectorAll('.cart').length === 2 && !document.body.classList.contains('loading'));
    await page.waitForFunction(() => document.querySelectorAll('.cart[data-state="ready"]').length === 2);
    assert.deepEqual(await page.locator('.cart .game').allTextContents(), ['\u5C1A\u672A\u4FDD\u5B58', '\u5C1A\u672A\u4FDD\u5B58']);
    assert.equal(await page.locator('#fill-status').textContent(), '2 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E · 2 \u4E2A\u53EF\u751F\u6210');
    assert.equal(await page.locator('#fill-open').textContent(), '\u751F\u6210\u6807\u7B7E\uFF082\uFF09');
    // Dropping a .gba file directly onto a card's face uploads the game and auto-fills its label preview.
    await page.locator('.cart .face').first().evaluate((face) => {
      const dt = new DataTransfer();
      dt.items.add(new File([new Uint8Array(0x150)], 'Epsilon.gba'));
      face.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
    });
    await page.waitForFunction(() => document.querySelectorAll('.cart[data-state="ready"]').length === 3);
    assert.equal(await page.locator('#banner').isVisible(), false);
    assert.equal(await page.locator('#uploads-wrap').isVisible(), true);
    assert.equal(await page.locator('#uploads-clear').isVisible(), true);
    await page.locator('#uploads-clear').click();
    assert.equal(await page.locator('#uploads-wrap').isVisible(), false);
    assert.equal(await page.locator('#fill-status').textContent(), '3 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E · 3 \u4E2A\u53EF\u751F\u6210');
    assert.equal(await page.locator('#fill-open').textContent(), '\u751F\u6210\u6807\u7B7E\uFF083\uFF09');
    state.names = ['Gamma', 'Delta'];
    // Rejecting a match on an unlabelled cart disables Save and updates the fillable count.
    await open('Gamma');
    assert.equal(await page.locator('#ed-save').isEnabled(), true);
    await page.locator('#ed-game').click();
    await page.locator('#ed-hits button.none').click();
    assert.equal(await page.locator('#ed-revert').isEnabled(), true);
    assert.equal(await page.locator('#ed-save').isDisabled(), true);
    await page.locator('#ed-close').click();
    await page.locator('#editor').waitFor({ state: 'hidden' });
    assert.equal(await page.locator('.cart').first().getAttribute('data-state'), 'needs-logo');
    assert.equal(await page.locator('.cart .game').first().textContent(), '');
    assert.equal(await page.locator('#fill-status').textContent(), '3 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E · 2 \u4E2A\u53EF\u751F\u6210');
    assert.equal(await page.locator('#fill-open').textContent(), '\u751F\u6210\u6807\u7B7E\uFF082\uFF09');
    await open('Gamma');
    await page.locator('#ed-game').click();
    await page.locator('#ed-hits button').first().click();
    await page.waitForFunction(() => !document.querySelector('#ed-save').disabled);
    await page.locator('#ed-close').click();
    await page.locator('#decision-discard').click();
    await page.reload();
    await page.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelectorAll('.cart[data-state="ready"]').length === 2);
    await page.locator('#fill-open').click();
    await page.locator('#fill-logo').click();
    await page.locator('#fill-cancel').click();
    assert.deepEqual(state.writes, []);
    await page.locator('#fill-open').click();
    await page.locator('#fill-logo').click();
    await page.locator('#fill-save').click();
    await page.locator('#fill').waitFor({ state: 'hidden' });
    assert.equal(state.writes.filter(([url]) => url.startsWith('/api/labels/')).length, 2);
    assert.deepEqual(await page.locator('.cart .game').allTextContents(), ['', '']);
    assert.equal(await page.locator('#shelf-fill').isVisible(), false);
    assert.equal(await page.locator('#fill-open').isVisible(), false);

    // Mixed missing labels and missing covers are filled in one pass from the same shelf bar.
    state.names = ['Gamma', 'Delta']; state.labelledNames = ['Gamma']; state.boxedNames = []; state.writes = [];
    await page.reload();
    await page.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelectorAll('.cart').length === 2 && !document.body.classList.contains('loading'));
    await page.waitForFunction(() => document.querySelector('#fill-open')?.textContent === '\u8865\u5168\u6807\u7B7E\u4E0E\u5C01\u9762\uFF082\uFF09' && document.querySelectorAll('.cart[data-state="ready"]').length === 1);
    assert.deepEqual(await page.locator('.cart .game').allTextContents(), ['\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58', '\u5C1A\u672A\u4FDD\u5B58']);
    assert.equal(await page.locator('#fill-status').textContent(), '1 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E · 1 \u5F20\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58');
    await page.locator('#fill-open').click();
    assert.equal(await page.locator('#fill-question').textContent(), '\u8981\u7528\u54EA\u79CD\u65B9\u5F0F\u751F\u6210\uFF1F\u8FD8\u4F1A\u4E00\u5E76\u4FDD\u5B58 1 \u5F20\u5DF2\u5339\u914D\u7684\u5C01\u9762\u3002');
    await page.locator('#fill-logo').click();
    await page.locator('#fill-save').click();
    await page.locator('#fill').waitFor({ state: 'hidden' });
    assert.equal(state.writes.filter(([url]) => url.startsWith('/api/labels/')).length, 1);
    assert.equal(state.writes.filter(([url]) => url.startsWith('/api/boxart/')).length, 2);
    assert.deepEqual(await page.locator('.cart .game').allTextContents(), ['', '']);
    assert.equal(await page.locator('#shelf-fill').isVisible(), false);

    // Labelled games that only lack covers save directly from the shelf bar with loading/error/empty states.
    state.labelledNames = null; state.labelled = true; state.boxedNames = []; state.writes = [];
    await page.reload();
    await page.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelector('#fill-open')?.textContent === '\u8865\u5168\u5C01\u9762\uFF082\uFF09');
    assert.deepEqual(await page.locator('.cart .game').allTextContents(), ['\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58', '\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58']);
    assert.equal(await page.locator('#fill-status').textContent(), '2 \u5F20\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58');
    state.failSave = true;
    await page.locator('#fill-open').click();
    assert.equal(await page.locator('#fill').isVisible(), false);
    await page.waitForFunction(() => !document.querySelector('#banner').hidden && !document.querySelector('#fill-open').disabled);
    assert.match(await page.locator('#banner').textContent(), /\u5C01\u9762: \u64CD\u4F5C\u5931\u8D25/);
    assert.equal(await page.locator('#fill-open').textContent(), '\u8865\u5168\u5C01\u9762\uFF082\uFF09');
    state.failSave = false; state.delay = 250; state.writes = [];
    await page.locator('#fill-open').click();
    assert.equal(await page.locator('#fill').isVisible(), false);
    assert.equal(await page.locator('#fill-open').isDisabled(), true);
    assert.equal(await page.locator('#fill-open').textContent(), '\u6B63\u5728\u4FDD\u5B58…');
    assert.match(await page.locator('#fill-status').textContent(), /\u6B63\u5728\u4FDD\u5B58 [12]\/2…/);
    await page.waitForFunction(() => document.querySelector('#shelf-fill').hidden);
    assert.equal(state.writes.filter(([url]) => url.startsWith('/api/labels/')).length, 0);
    assert.equal(state.writes.filter(([url]) => url.startsWith('/api/boxart/')).length, 2);
    assert.deepEqual(await page.locator('.cart .game').allTextContents(), ['', '']);
    state.delay = 0; state.labelled = false; state.boxedNames = null;

    // Fallback catalog matching resolves a patched ROM with a modified CRC, and editor search strips tags while keeping subtitles.
    state.names = ['Gamma - Part II (USA) [T-Zh]'];
    state.crcs = { 'Gamma - Part II (USA) [T-Zh].gba': 99 };
    state.art = true;
    const patchedDat = 'clrmamepro (\n\tname "GBA"\n)\ngame (\n\tname "Gamma - Part II (USA)"\n\tregion "USA"\n\trom ( name "Gamma - Part II (USA).gba" size 512 crc 00000001 )\n)\n';
    await page.route('https://**/*.dat', (route) => route.fulfill({ status: 200, body: patchedDat, contentType: 'text/plain' }));
    await page.reload();
    await page.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelector('.cart')?.dataset.state === 'ready');
    await open('Gamma');
    assert.equal(await page.locator('#ed-game span').textContent(), 'Gamma - Part II (USA)');
    await page.locator('#ed-game').click();
    assert.equal(await page.locator('#ed-query').inputValue(), 'Gamma - Part II');
    await page.locator('#ed-close').click();
    await page.unroute('https://**/*.dat');
    state.crcs = null;

    state.art = false; state.names = ['NoArt']; state.writes = [];
    await page.reload();
    await page.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelector('.cart')?.dataset.state === 'needs-logo');
    assert.equal(await page.locator('#fill-status').isVisible(), true);
    assert.equal(await page.locator('#fill-status').textContent(), '1 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E');
    assert.equal(await page.locator('#fill-open').isVisible(), false);

    // Compact layout keeps actions reachable; English uses the same complete flow.
    await page.setViewportSize({ width: 390, height: 844 });
    await more('NoArt').click();
    const menuBounds = await page.locator('#game-menu').boundingBox();
    assert.ok(menuBounds.x >= 0 && menuBounds.x + menuBounds.width <= 390 && menuBounds.y + menuBounds.height <= 844);
    if (output) await page.screenshot({ path: path.join(output, 'studio-shelf-menu-mobile.png') });
    await page.keyboard.press('Escape');
    // Near the bottom edge the dropdown opens upwards, within the viewport.
    await page.setViewportSize({ width: 390, height: 600 });
    await more('NoArt').click();
    const compactBounds = await page.locator('#game-menu').boundingBox();
    const triggerBounds = await more('NoArt').boundingBox();
    assert.ok(compactBounds.y + compactBounds.height <= 600);
    assert.ok(compactBounds.y >= triggerBounds.y + triggerBounds.height || compactBounds.y + compactBounds.height <= triggerBounds.y);
    const scrolled = await page.evaluate(() => { const y = scrollY; window.scrollBy(0, 60); return scrollY !== y; });
    if (scrolled) await page.locator('#game-menu').waitFor({ state: 'hidden' });
    await page.setViewportSize({ width: 390, height: 844 });
    await open('NoArt');
    // Finish buttons remain enabled even when the shell starts on Automatic.
    assert.equal(await page.locator('#ed-finish button[data-value="glitter"]').isEnabled(), true);
    assert.equal(await page.locator('#ed-save').isVisible(), true);
    assert.equal(await page.locator('#editor .cart-more, #ed-manage, #ed-delete').count(), 0);
    assert.ok(await page.locator('#editor').evaluate((el) => el.getBoundingClientRect().right <= innerWidth));
    if (output) await page.screenshot({ path: path.join(output, 'studio-mobile.png') });
    await page.locator('#ed-close').click();
    const english = await browser.newPage({ locale: 'en-US', viewport: { width: 1280, height: 900 } });
    english.on('pageerror', (error) => errors.push(error.message));
    await english.route('https://**/*', (route) => route.fulfill({ status: 200, body: '' }));
    await english.goto(`http://127.0.0.1:${server.address().port}/?pin=${state.pin}`);
    await english.waitForFunction(() => document.body.dataset.ready === 'true' && document.querySelector('.cart')?.dataset.state === 'needs-logo');
    await english.locator('.cart').click();
    await english.waitForFunction(() => !document.querySelector('#ed-shell').disabled);
    assert.equal(await english.locator('#ed-save').textContent(), 'Save');
    assert.equal(await english.locator('#ed-revert').textContent(), 'Reset');
    await english.locator('#ed-close').click();
    await english.locator('.cart-more').click();
    await english.locator('#game-delete').click();
    assert.match(await english.locator('#decision-text').textContent(), /Save files will be kept/);
    if (output) await english.screenshot({ path: path.join(output, 'studio-delete-desktop.png') });
    // Native single-cart editing never reads another ROM or exposes ROM upload pickers, while keeping custom image pickers available.
    state.names = ['Alpha', 'Beta']; state.labelledNames = null; state.boxedNames = null;
    state.labelled = true; state.art = true; state.writes = [];
    state.shells = 'Alpha = auto 123456 solid\nBeta = auto 654321 solid\n'; state.version++;
    const local = await browser.newPage({ locale: 'zh-CN', viewport: { width: 960, height: 640 } });
    local.on('pageerror', (error) => errors.push(error.message));
    await local.route('https://**/*', (route) => route.fulfill({ status: 200,
      body: route.request().url().endsWith('.dat') ? dat : route.request().url().endsWith('.png') ? png : '' }));
    await local.addInitScript(() => { window.closedCount = 0; window.AndroidStudio = { close: () => window.closedCount++ }; });
    const requests = [];
    local.on('request', (request) => requests.push(request.url()));
    const localUrl = `http://127.0.0.1:${server.address().port}/?local=1&pin=${state.pin}&lang=en&platform=GBA&file=Alpha.gba`;
    await local.goto(localUrl);
    await local.waitForFunction(() => window.__studio?.idle() && window.__studio.editing() === 'Alpha');
    assert.equal(await local.locator('#ed-save').textContent(), 'Save');
    assert.equal(requests.some((url) => url.includes('/api/games/GBA/Beta')), false);
    for (const selector of ['.intro', '.mast', '#tabs', '#upload-label'])
      assert.equal(await local.locator(selector).isVisible(), false, selector);
    for (const selector of ['#ed-tiles [data-kind="customLogo"]', '#ed-tiles [data-kind="customLabel"]', '#ed-box-tiles [data-kind="custom"]'])
      assert.equal(await local.locator(selector).isVisible(), true, selector);
    for (const [width, height] of [[714, 488], [640, 432], [600, 600], [960, 640], [960, 720], [720, 720], [1280, 720]]) {
      await local.setViewportSize({ width, height });
      await local.locator('.ed-body').evaluate((el) => { el.scrollTop = 0; });
      const save = await local.locator('#ed-save').boundingBox();
      assert.ok(save.x >= 0 && save.y >= 0 && save.x + save.width <= width && save.y + save.height <= height);
      if (width <= 1100) {
        const preview = await local.locator('#ed-face').boundingBox();
        const props = await local.locator('.props').boundingBox();
        const finish = await local.locator('#ed-finish').boundingBox();
        assert.ok(preview.x + preview.width <= props.x, 'preview beside controls');
        assert.ok(preview.width <= 320 && preview.height <= 320, 'bounded preview');
        assert.ok(finish.y + finish.height <= save.y, 'everyday controls visible without scrolling');
        assert.equal(await local.locator('.ed-body').evaluate((el) => el.scrollWidth <= el.clientWidth), true);
      }
      if (width === 960) {
        const box = await local.locator('#ed-box-tiles .tile').evaluateAll((tiles) =>
          tiles.filter((tile) => !tile.hidden).map((tile) => ({ top: tile.offsetTop, width: tile.offsetWidth })));
        assert.equal(box.length, 3);
        assert.ok(box.every((tile) => tile.width >= 120), 'box tiles keep a readable width');
        assert.ok(box.some((tile) => tile.top > box[0].top), 'narrow box-art column wraps instead of squeezing three across');
      }
      if (output) await local.screenshot({ path: path.join(output, `local-studio-${width}x${height}.png`) });
    }
    await local.locator('#ed-shell').click();
    assert.equal(await local.evaluate(() => slotStudioBack()), true);
    assert.equal(await local.locator('#ed-shell-pal').isVisible(), false);
    assert.equal(await local.locator('#editor').isVisible(), true);
    await local.locator('#ed-shell').click();
    await local.locator('#ed-shell-presets button[data-value="auto 249c60 clear"]').click();
    await local.evaluate(() => slotStudioBack());
    await local.locator('#decision').waitFor({ state: 'visible' });
    await local.evaluate(() => slotStudioBack());
    await local.locator('#decision').waitFor({ state: 'hidden' });
    assert.equal(await local.evaluate(() => window.closedCount), 0);
    await local.evaluate(() => slotStudioBack());
    await local.locator('#decision-discard').click();
    await local.waitForFunction(() => window.closedCount === 1);
    await local.goto(localUrl);
    await local.waitForFunction(() => window.__studio?.idle() && !document.querySelector('#ed-shell').disabled);
    await local.locator('#ed-shell').click();
    await local.locator('#ed-shell-presets button[data-value="auto 249c60 clear"]').click();
    state.failSave = true;
    await local.locator('#ed-save').click();
    await local.waitForFunction(() => !document.getElementById('ed-save').disabled);
    assert.equal(await local.evaluate(() => window.closedCount), 0);
    assert.equal(await local.locator('#editor').isVisible(), true);
    state.failSave = false;
    await local.locator('#ed-save').click();
    await local.waitForFunction(() => window.closedCount === 1);
    assert.match(state.shells, /Alpha = auto 249c60 clear/);
    assert.match(state.shells, /Beta =/);
    await local.goto(localUrl.replace('Alpha.gba', 'Missing.gba'));
    await local.locator('#banner').waitFor({ state: 'visible' });
    assert.equal(await local.locator('#editor').isVisible(), false);
    assert.equal(await local.locator('.cart').count(), 0);
    assert.equal(await local.evaluate(() => slotStudioBack()), false);
    await local.goto(`http://127.0.0.1:${server.address().port}/?local=1&pin=${state.pin}&lang=zh`);
    await local.waitForFunction(() => window.__studio?.idle());
    assert.equal(await local.locator('.cart').count(), 2);
    assert.equal(await local.evaluate(() => slotStudioBack()), false);

    // Stalled matching is visible, preserves the existing preview and leaves local editing available.
    for (const stalled of ['index', 'database']) {
      state.shells = 'Alpha = auto 123456 solid\nBeta = auto 654321 solid\n'; state.version++;
      state.writes = [];
      const offline = await browser.newPage({ viewport: { width: 960, height: 720 } });
      offline.on('pageerror', (error) => errors.push(error.message));
      const held = [];
      const pendingRequest = offline.waitForRequest((request) => stalled === 'index'
        ? request.url().endsWith('/art/index.json') : request.url().endsWith('.dat'), { timeout: 5000 });
      const hold = (route, body, contentType) => new Promise((resolve) => {
        held.push(async () => { await route.fulfill({ status: 200, body, contentType }); resolve(); });
      });
      await offline.route('https://**/*', (route) => {
        const url = route.request().url();
        if (stalled === 'database' && url.endsWith('.dat')) return hold(route, dat, 'text/plain');
        return route.fulfill({ status: 200, body: url.endsWith('.dat') ? dat : url.endsWith('.png') ? png : '' });
      });
      if (stalled === 'index') await offline.route('**/art/index.json', (route) => hold(route, '{}', 'application/json'));
      await offline.addInitScript(() => { window.closedCount = 0; window.AndroidStudio = { close: () => window.closedCount++ }; });
      try {
        await offline.goto(localUrl);
        await pendingRequest;
        await offline.waitForFunction(() => document.getElementById('editor').open && !document.getElementById('ed-shell').disabled, null, { timeout: 3000 });
        assert.equal(await offline.locator('#ed-art-status-text').textContent(), 'Matching game…');
        assert.equal(await offline.locator('#ed-art-status .spinner').isVisible(), true);
        assert.equal(await offline.locator('#ed-face').isVisible(), true);
        for (const kind of ['real', 'logo']) {
          assert.equal(await offline.locator(`#ed-tiles [data-kind="${kind}"]`).getAttribute('aria-busy'), 'true');
          assert.equal(await offline.locator(`#ed-tiles [data-kind="${kind}"] small`).textContent(), 'Loading…');
        }
        assert.equal(await offline.locator('#ed-tiles [data-kind="customLabel"]').isEnabled(), true);
        assert.equal(await offline.locator('#ed-revert').isDisabled(), true);
        await offline.locator('#ed-shell').click();
        await offline.locator('#ed-shell-presets button[data-value="auto 249c60 clear"]').click();
        assert.equal(await offline.locator('#ed-save').isEnabled(), true);
        if (stalled === 'index') {
          // Saving uses only the loopback API while the artwork request is still held.
          await offline.locator('#ed-save').click();
          await offline.waitForFunction(() => window.closedCount === 1);
          assert.match(state.shells, /Alpha = auto 249c60 clear/);
          assert.equal(state.writes.length, 1);
        }
        for (const release of held.splice(0)) await release();
        await offline.waitForFunction(() => window.__studio.idle());
        if (stalled === 'database') {
          assert.equal(await offline.locator('#ed-shell-custom').inputValue(), '#249c60');
          assert.equal(await offline.locator('#ed-revert').isEnabled(), true);
          await offline.locator('#ed-revert').click();
          assert.equal(await offline.locator('#ed-shell-custom').inputValue(), '#123456');
          assert.equal(await offline.locator('#ed-revert').isDisabled(), true);
          await offline.evaluate(() => slotStudioBack());
          await offline.waitForFunction(() => window.closedCount === 1);
          assert.equal(await offline.locator('#decision').isVisible(), false);
        }
      } finally {
        for (const release of held.splice(0)) await release();
        await offline.close();
      }
    }
    // Separate image downloads distinguish errors from absent artwork, and Retry stays in place.
    state.names = ['Alpha']; state.labelled = true; state.boxedNames = []; state.writes = [];
    const artwork = await browser.newPage({ viewport: { width: 960, height: 640 } });
    artwork.on('pageerror', (error) => errors.push(error.message));
    await artwork.route('https://**/*', (route) => route.fulfill({ status: 200,
      body: route.request().url().endsWith('.dat') ? dat : png }));
    let releaseLogo;
    let failReal = true;
    await artwork.route('**/art/GBA/wheel/*.png', (route) => new Promise((resolve) => {
      releaseLogo = async () => { await route.fulfill({ status: 200, body: png }); resolve(); };
    }));
    await artwork.route('**/art/GBA/support-texture/*.png', (route) => route.fulfill({ status: failReal ? 503 : 200, body: failReal ? '' : png }));
    try {
      await artwork.goto(localUrl);
      await artwork.waitForFunction(() => document.querySelector('#ed-art-status-text').textContent === 'Fetching images…');
      assert.equal(await artwork.locator('#ed-face').isVisible(), true);
      assert.equal(await artwork.locator('#ed-shell').isEnabled(), true);
      assert.equal(await artwork.locator('#ed-tiles [data-kind="logo"]').getAttribute('aria-busy'), 'true');
      await artwork.waitForFunction(() => document.querySelector('[data-kind="fetched"]').dataset.artState === 'ready');
      assert.equal(await artwork.locator('#ed-tiles [data-kind="logo"]').getAttribute('aria-busy'), 'true');
      if (output) await artwork.screenshot({ path: path.join(output, 'artwork-fetching.png') });
      // A custom choice made before the automatic result arrives wins afterwards.
      const chooserEvent = artwork.waitForEvent('filechooser');
      await artwork.locator('#ed-tiles [data-kind="customLabel"]').click();
      await (await chooserEvent).setFiles({ name: 'label.png', mimeType: 'image/png', buffer: png });
      await artwork.waitForFunction(() => document.querySelector('[data-kind="customLabel"]').getAttribute('aria-pressed') === 'true');
      assert.equal(await artwork.locator('#ed-save').isEnabled(), true);
      await releaseLogo(); releaseLogo = null;
      await artwork.waitForFunction(() => document.querySelector('#ed-art-status-text').textContent === 'Couldn’t fetch images');
      assert.equal(await artwork.locator('[data-kind="customLabel"]').getAttribute('aria-pressed'), 'true');
      assert.equal(await artwork.locator('[data-kind="real"]').getAttribute('data-art-state'), 'error');
      assert.equal(await artwork.locator('[data-kind="logo"]').getAttribute('data-art-state'), 'ready');
      if (output) await artwork.screenshot({ path: path.join(output, 'artwork-retry.png') });
      failReal = false;
      await artwork.locator('#ed-art-action').click();
      await artwork.waitForFunction(() => document.querySelector('#ed-art-status').hidden);
      assert.equal(await artwork.locator('[data-kind="customLabel"]').getAttribute('aria-pressed'), 'true');
      assert.equal(await artwork.locator('[data-kind="real"]').getAttribute('data-art-state'), 'ready');
    } finally {
      if (releaseLogo) await releaseLogo();
      await artwork.close();
    }
    // A database failure can be retried; a completed search with no match offers manual selection.
    state.labelled = false; state.art = false;
    const unmatched = await browser.newPage({ locale: 'zh-CN', viewport: { width: 720, height: 720 } });
    unmatched.on('pageerror', (error) => errors.push(error.message));
    let failDatabase = true;
    await unmatched.route('https://**/*', (route) => route.fulfill({
      status: failDatabase ? 503 : 200, body: '' }));
    try {
      await unmatched.goto(localUrl.replace('lang=en', 'lang=zh'));
      await unmatched.waitForFunction(() => document.querySelector('#ed-art-status-text').textContent === '\u6E38\u620F\u5339\u914D\u5931\u8D25');
      assert.equal(await unmatched.locator('[data-kind="customLabel"]').isEnabled(), true);
      assert.equal(await unmatched.locator('#ed-shell').isEnabled(), true);
      assert.equal(await unmatched.locator('#ed-face').isVisible(), true);
      failDatabase = false;
      await unmatched.locator('#ed-art-action').click();
      await unmatched.waitForFunction(() => document.querySelector('#ed-art-status-text').textContent === '\u672A\u627E\u5230\u5339\u914D\u6E38\u620F');
      assert.equal(await unmatched.locator('[data-kind="real"] small').textContent(), '\u6682\u65E0\u56FE\u7247');
      assert.equal(await unmatched.locator('[data-kind="logo"] small').textContent(), '\u6682\u65E0\u56FE\u7247');
      assert.equal(await unmatched.locator('[data-kind="fetched"] small').textContent(), '\u6682\u65E0\u56FE\u7247');
      if (output) await unmatched.screenshot({ path: path.join(output, 'artwork-no-match-square.png') });
      await unmatched.locator('#ed-art-action').click();
      assert.equal(await unmatched.locator('#ed-games').isVisible(), true);
    } finally { await unmatched.close(); }
    state.names = [];
    await local.reload();
    await local.waitForFunction(() => document.body.dataset.ready === 'true');
    assert.equal(await local.locator('#banner').textContent(), '\u8FD8\u6CA1\u6709\u6E38\u620F');
    await local.route('**/api/library', (route) => route.fulfill({ status: 503, body: '{"error":"library"}', contentType: 'application/json' }));
    await local.reload();
    await local.waitForFunction(() => !document.getElementById('banner').hidden);
    assert.match(await local.locator('#banner').textContent(), /\u65E0\u6CD5\u6253\u5F00 Slot Manager/);
    assert.equal(await local.locator('#pair').isVisible(), false);
    assert.equal(await local.locator('#loader').isVisible(), false);
    assert.deepEqual(errors, []);
    console.log('PASS editor/shelf: save/reset, menus, bilingual layout; matching/image progress, independent tiles, retained preview, custom choice during loading, retry and no-match actions; seven viewport sizes');
  } finally { await browser.close(); }
})().catch((error) => { console.error(error); process.exitCode = 1; }).finally(() => server.close());
