import { test } from 'node:test';
import assert from 'node:assert/strict';
import { boxAvailable, boxBytesOf, boxRemove, boxRemoved, boxTurnedUp, boxWears, boxWritten, boxWrite, chooseBox, squareCrop, useCustomBox } from './boxart.js';

const bytes = (n) => new Uint8Array([n]);
const cart = (over = {}) => ({
  game: 'Drill Dozer (USA)',
  rejected: false,
  boxOnCard: false,
  boxCardBytes: null,
  boxBytes: bytes(1),
  customBoxBytes: null,
  boxChoice: null,
  error: null,
  ...over,
});

test('an empty place is filled with the fetched image, and a taken one is left alone', () => {
  assert.equal(boxWears(cart()), 'fetched');
  assert.deepEqual(boxWrite(cart()), { bytes: bytes(1), replace: false });
  assert.equal(boxWears(cart({ boxOnCard: true })), 'card');
  assert.equal(boxWrite(cart({ boxOnCard: true })), null);
});

test('a cart with no match, no image or a read error has nothing to write', () => {
  assert.equal(boxWears(cart({ game: null })), 'none');
  assert.equal(boxWears(cart({ rejected: true })), 'none');
  assert.equal(boxWears(cart({ boxBytes: null })), 'none');
  assert.equal(boxWrite(cart({ error: 'x' })), null);
  assert.equal(boxAvailable(cart({ rejected: true }), 'fetched'), false);
});

test('choosing the fetched image over one on the card replaces it, choosing the card keeps it', () => {
  const c = cart({ boxOnCard: true, boxCardBytes: bytes(9) });
  chooseBox(c, 'fetched');
  assert.equal(boxWears(c), 'fetched');
  assert.deepEqual(boxWrite(c), { bytes: bytes(1), replace: true });
  chooseBox(c, 'card');
  assert.equal(boxWears(c), 'card');
  assert.equal(boxWrite(c), null);
  assert.deepEqual(boxBytesOf(c), bytes(9));
  chooseBox(c, 'none');
  assert.equal(boxWears(c), 'none');
  assert.equal(boxWrite(c), null);
  assert.equal(boxRemove(c), true);
  boxRemoved(c);
  assert.equal(c.boxOnCard, false);
  assert.equal(boxRemove(c), false);
});

test('a picture of the person’s own is written, and replaces only what is there', () => {
  const c = cart();
  useCustomBox(c, bytes(5));
  assert.equal(boxWears(c), 'custom');
  assert.deepEqual(boxWrite(c), { bytes: bytes(5), replace: false });
  c.boxOnCard = true;
  assert.deepEqual(boxWrite(c), { bytes: bytes(5), replace: true });
  assert.equal(boxWears(cart({ boxChoice: 'custom' })), 'none');
});

test('a fetched choice that lost its game falls back to what is on the card, else nothing', () => {
  assert.equal(boxWears(cart({ boxChoice: 'fetched', game: null, boxOnCard: true })), 'card');
  assert.equal(boxWears(cart({ boxChoice: 'fetched', game: null })), 'none');
  assert.equal(boxWears(cart({ boxChoice: 'card' })), 'none');
});

test('writing settles a cart, and one that turned up meanwhile is not overwritten', () => {
  const c = cart();
  boxWritten(c, bytes(1));
  assert.deepEqual([c.boxOnCard, c.boxChoice, boxWrite(c)], [true, null, null]);
  const d = cart({ boxChoice: 'fetched' });
  boxTurnedUp(d);
  assert.equal(boxWears(d), 'card');
  assert.equal(boxWrite(d), null);
});

test('a picture is cropped to its centred square and never enlarged past the saved size', () => {
  assert.deepEqual(squareCrop(1600, 900), { sx: 350, sy: 0, side: 900, out: 512 });
  assert.deepEqual(squareCrop(300, 500), { sx: 0, sy: 100, side: 300, out: 300 });
  assert.deepEqual(squareCrop(512, 512), { sx: 0, sy: 0, side: 512, out: 512 });
});
