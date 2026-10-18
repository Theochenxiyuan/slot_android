import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const html = fs.readFileSync(new URL('./index.html', import.meta.url), 'utf8');
const js = fs.readFileSync(new URL('./studio.js', import.meta.url), 'utf8');

test('the device page defines every literal element used by its controller', () => {
  const ids = new Set([...html.matchAll(/id="([^"]+)"/g)].map((m) => m[1]));
  for (const [, id] of js.matchAll(/\$\('([^']+)'\)/g)) {
    assert.ok(ids.has(id), `Missing #${id}`);
  }
});

test('the device fork keeps individual saving without a write bar or build placeholder', () => {
  assert.match(html, /<title>Slot Manager<\/title>/);
  assert.match(html, /<h1 class="title">Slot Manager<\/h1>/);
  assert.doesNotMatch(html, /id="(?:write-bar|write|eject|build)"/);
  assert.doesNotMatch(js, /const BUILD\s*=/);
  assert.match(js, /async function saveOne\(/);
});

test('bundled Android web assets match their editable sources', () => {
  for (const name of fs.readdirSync(new URL('./', import.meta.url))) {
    if (!/\.(js|css|html)$/.test(name)) continue;
    assert.deepEqual(fs.readFileSync(new URL(`../../../app/src/main/assets/studio/${name}`, import.meta.url)),
      fs.readFileSync(new URL(name, import.meta.url)), name);
  }
});
