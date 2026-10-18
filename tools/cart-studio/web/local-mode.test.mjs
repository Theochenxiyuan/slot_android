import test from 'node:test';
import assert from 'node:assert/strict';
import { studioMode, singleListing, studioBack } from './local-mode.js';

test('single-cart parameters preserve Unicode, spaces and literal plus signs', () => {
  const mode = studioMode('?local=1&platform=GBA&file=%E6%B8%B8%E6%88%8F%20%2B%20Bonus.gba');
  assert.deepEqual(mode, { local: true, single: true, target: { platform: 'GBA', file: '\u6E38\u620F + Bonus.gba' } });
});

test('invalid and incomplete targets never fall back to whole-library editing', () => {
  for (const query of ['?platform=NES&file=x.nes', '?platform=GB', '?file=x.gb', '?platform=GB&file=../x.gb', '?platform=GB&file=x%5Cy.gb', '?platform=GB&file=.x.gb', '?platform=GB&file=x%00.gb']) {
    const mode = studioMode(query);
    assert.equal(mode.single, true);
    assert.equal(mode.target, null);
    assert.deepEqual(singleListing({ carts: [{ platform: 'GB', file: 'x.gb' }] }, mode).carts, []);
  }
});

test('single mode reads only the exact target while keeping shared shell and image maps', () => {
  const listing = { carts: [{ platform: 'GB', file: 'x.gb' }, { platform: 'GBA', file: 'x.gba' }], labels: [], boxarts: [] };
  const selected = singleListing(listing, studioMode('?platform=GB&file=x.gb'));
  assert.deepEqual(selected.carts, [listing.carts[0]]);
  assert.equal(selected.labels, listing.labels);
  assert.equal(singleListing(listing, studioMode('')), listing);
});

function fixture() {
  const calls = [];
  const elements = Object.fromEntries(['decision', 'reauth', 'fill', 'game-menu'].map((id) => [id, {
    open: false, hidden: true,
    dispatchEvent: (event) => { calls.push(id); if (id === 'decision') { event.preventDefault(); elements[id].open = false; } return !event.defaultPrevented; },
    close() { this.open = false; },
  }]));
  let editing = false, busy = false;
  return { elements, calls, editing: () => editing = true, busy: () => busy = true,
    back: () => studioBack({ getElementById: (id) => elements[id] }, { current: () => editing, back: () => calls.push('editor') }, { close: () => calls.push('menu') }, () => busy) };
}

test('native back resolves the top confirmation before touching the editor or fill dialog', () => {
  const f = fixture(); f.editing(); f.elements.decision.open = f.elements.fill.open = true;
  assert.equal(f.back(), true); assert.deepEqual(f.calls, ['decision']);
  assert.equal(f.elements.fill.open, true);
  f.back(); assert.deepEqual(f.calls, ['decision', 'fill']);
});

test('native back closes a menu then delegates editor popovers and unsaved checks', () => {
  const f = fixture(); f.editing(); f.elements['game-menu'].hidden = false;
  f.back(); assert.deepEqual(f.calls, ['menu']);
  f.elements['game-menu'].hidden = true; f.back(); assert.deepEqual(f.calls, ['menu', 'editor']);
});

test('native back exits at the library root but never interrupts a write', () => {
  const f = fixture(); assert.equal(f.back(), false);
  f.busy(); f.elements.fill.open = true;
  assert.equal(f.back(), true); assert.equal(f.elements.fill.open, true); assert.deepEqual(f.calls, []);
});
