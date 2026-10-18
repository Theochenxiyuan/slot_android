import { test } from 'node:test';
import assert from 'node:assert/strict';
import { acknowledge, amend, canRevert, commit, revert, visit, visited } from './history.js';

const cart = (over = {}) => ({ game: 'Tetris', choice: 'card', deep: null, userHue: false, shell: '', looking: false, ...over });

test('reset restores the saved baseline across all edits', () => {
  const c = cart();
  visit(c);
  commit(c, () => { c.shell = 'auto 123456 solid'; c.deep = [1, 2, 3]; c.choice = 'logo'; });
  assert.equal(canRevert(c), true);
  assert.equal(revert(c), true);
  assert.deepEqual([c.shell, c.deep, c.choice], ['', null, 'card']);
  assert.equal(canRevert(c), false);
});

test('the first edit captures a baseline even outside the editor', () => {
  const c = cart();
  commit(c, () => { c.choice = 'customLabel'; c.customLabelBytes = new Uint8Array([1]); });
  assert.equal(revert(c), true);
  assert.equal(c.choice, 'card');
});

test('equal colours and no-op changes do not enable reset', () => {
  const c = cart({ deep: [1, 2, 3] });
  assert.equal(commit(c, () => { c.deep = [1, 2, 3]; }), false);
  assert.equal(canRevert(c), false);
  assert.equal(revert(c), false);
});

test('saving advances the baseline, including after repeated saves', () => {
  const c = cart();
  commit(c, () => { c.shell = 'a'; });
  visit(c);
  commit(c, () => { c.shell = 'b'; });
  revert(c);
  assert.equal(c.shell, 'a');
});

test('partial saves advance only the successful fields', () => {
  const c = cart();
  visit(c);
  commit(c, () => { c.choice = 'customLabel'; c.shell = 'a'; });
  acknowledge(c, ['choice']);
  assert.equal(canRevert(c), true);
  revert(c);
  assert.equal(c.choice, 'customLabel');
  assert.equal(c.shell, '');
});

test('downloaded assets do not trigger unsaved-change protection', () => {
  const c = cart();
  visit(c);
  c.logoBytes = new Uint8Array([1]);
  c.boxBytes = new Uint8Array([2]);
  c.boxHue = 32;
  assert.equal(canRevert(c), false);
});

test('resetting a game match also restores the matching cover bytes', () => {
  const oldBox = new Uint8Array([1]);
  const c = cart({ boxBytes: oldBox });
  visit(c);
  commit(c, () => { c.game = 'Other'; });
  c.boxBytes = new Uint8Array([2]);
  revert(c);
  assert.equal(c.game, 'Tetris');
  assert.equal(c.boxBytes, oldBox);
});

test('initial file reading blocks reset; optional matching leaves draft reset available', () => {
  const c = cart({ reading: true, looking: true });
  visit(c);
  assert.equal(visited(c), false);
  c.reading = false;
  visit(c);
  commit(c, () => { c.game = 'Other'; });
  c.looking = true;
  assert.equal(canRevert(c), true);
  assert.equal(revert(c), true);
  assert.equal(c.game, 'Tetris');
});

test('automatic fill can amend the baseline without committing shell edits', () => {
  const c = cart({ choice: null });
  visit(c);
  commit(c, () => { c.shell = 'a'; });
  c.choice = 'real';
  amend(c, (snap) => { snap.choice = 'real'; });
  revert(c);
  assert.deepEqual([c.choice, c.shell], ['real', '']);
});
