import { test } from 'node:test';
import assert from 'node:assert/strict';
import { saveCart } from './save.js';
import { canRevert, commit, revert, visit } from './history.js';

const cart = (stem) => ({ platform: 'GBA', stem, choice: 'card', shell: '', shellOnCard: '', looking: false, boxOnCard: true, boxChoice: null });
function setup() {
  const calls = [];
  const errors = [];
  const source = {
    write: async (platform, stem) => { calls.push(['label', stem]); return 'written'; },
    removeLabel: async (platform, stem) => { calls.push(['remove-label', stem]); return 'deleted'; },
    writeBoxart: async (platform, stem) => { calls.push(['box', stem]); return 'written'; },
    removeBoxart: async (platform, stem) => { calls.push(['remove-box', stem]); return 'deleted'; },
  };
  return { calls, errors, api: {
    source, labelReady: (c) => c.choice !== 'card' && c.choice !== 'none',
    labelRemove: (c) => !!c.existingPng && c.choice === 'none',
    makeLabel: () => ({ png: new Uint8Array([1]), face: new Uint8Array([2]) }),
    writeShell: async (c) => { calls.push(['shell', c.stem]); },
    failed: (part, error) => errors.push([part, error.message]),
  } };
}

test('saving one game writes its label, shell and cover without touching another draft', async () => {
  const a = cart('A');
  const b = cart('B');
  visit(a); visit(b);
  for (const c of [a, b]) commit(c, () => { c.choice = 'customLabel'; c.shell = 'new'; c.boxChoice = 'custom'; c.customBoxBytes = new Uint8Array([3]); });
  const { api, calls } = setup();
  assert.equal(await saveCart(a, api), true);
  assert.deepEqual(calls, [['label', 'A'], ['shell', 'A'], ['box', 'A']]);
  assert.equal(canRevert(a), false);
  assert.equal(canRevert(b), true);
  assert.equal(b.existingPng, undefined);
});

test('partial failure keeps only the failed parts pending and reset respects saved parts', async () => {
  const c = cart('A');
  visit(c);
  commit(c, () => { c.choice = 'customLabel'; c.shell = 'new'; });
  const { api, errors } = setup();
  api.writeShell = async () => { throw new Error('offline'); };
  assert.equal(await saveCart(c, api), false);
  assert.deepEqual(errors, [['shell', 'offline']]);
  assert.equal(c.choice, 'card');
  assert.ok(c.existingPng);
  assert.equal(canRevert(c), true);
  revert(c);
  assert.equal(c.choice, 'card');
  assert.equal(c.shell, '');
});

test('retry does not rewrite a label that succeeded before a shell failure', async () => {
  const c = cart('A');
  visit(c);
  commit(c, () => { c.choice = 'customLabel'; c.shell = 'new'; });
  const { api, calls } = setup();
  const writeShell = api.writeShell;
  api.writeShell = async () => { throw new Error('offline'); };
  await saveCart(c, api);
  api.writeShell = writeShell;
  assert.equal(await saveCart(c, api), true);
  assert.deepEqual(calls, [['label', 'A'], ['shell', 'A']]);
  assert.equal(c.shellOnCard, 'new');
  assert.equal(canRevert(c), false);
});

test('a skipped write remains pending, never claiming that the draft was saved', async () => {
  const c = cart('A');
  visit(c);
  commit(c, () => { c.choice = 'customLabel'; });
  const { api, errors } = setup();
  api.source.write = async () => 'skipped';
  assert.equal(await saveCart(c, api), false);
  assert.equal(c.choice, 'customLabel');
  assert.equal(canRevert(c), true);
  assert.deepEqual(errors, [['label', 'conflict']]);
});

test('saving a game with no writable changes returns false and produces no writes', async () => {
  const c = cart('A');
  visit(c);
  commit(c, () => { c.rejected = true; });
  const { api, calls } = setup();
  assert.equal(await saveCart(c, api), false);
  assert.deepEqual(calls, []);
  assert.equal(canRevert(c), true);
});

test('removing a saved label and cover deletes both from the device and settles the baseline', async () => {
  const c = { ...cart('A'), existingPng: new Uint8Array([1]), existing: new Uint8Array([2]) };
  visit(c);
  commit(c, () => { c.choice = 'none'; c.boxChoice = 'none'; });
  const { api, calls } = setup();
  assert.equal(await saveCart(c, api), true);
  assert.deepEqual(calls, [['remove-label', 'A'], ['remove-box', 'A']]);
  assert.equal(c.existingPng, null);
  assert.equal(c.boxOnCard, false);
  assert.equal(canRevert(c), false);
});
