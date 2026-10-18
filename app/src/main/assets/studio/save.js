import { acknowledge, LABEL_FIELDS, BOX_FIELDS, visit } from './history.js';
import { boxRemove, boxRemoved, boxWrite, boxWritten } from './boxart.js';

// A failed part remains pending. Successful parts advance their reset baseline immediately.
export async function saveCart(c, { source, labelReady, labelRemove = () => false, makeLabel, writeShell, failed, stopped = () => false }) {
  let ok = true;
  let wrote = false;
  if (labelReady(c)) {
    wrote = true;
    try {
      const { png, face } = makeLabel(c);
      if (await source.write(c.platform, c.stem, png, !!c.existingPng) !== 'written') throw new Error('conflict');
      c.existingPng = png;
      c.existing = face;
      c.choice = 'card';
      c.byHand = false;
      acknowledge(c, LABEL_FIELDS);
    } catch (error) { ok = false; failed('label', error); }
  } else if (labelRemove(c)) {
    wrote = true;
    try {
      if (await source.removeLabel(c.platform, c.stem) !== 'deleted') throw new Error('conflict');
      c.existingPng = null;
      c.existing = null;
      source.labels?.delete(`${c.platform}/${c.stem.normalize('NFC')}`);
      acknowledge(c, LABEL_FIELDS);
    } catch (error) { ok = false; failed('label', error); }
  }
  if (stopped()) return false;
  if (c.shell !== c.shellOnCard) {
    wrote = true;
    try {
      await writeShell(c);
      c.shellOnCard = c.shell;
      acknowledge(c, ['shell']);
    } catch (error) { ok = false; failed('shell', error); }
  }
  if (stopped()) return false;
  const box = source.writeBoxart && boxWrite(c);
  if (box) {
    wrote = true;
    try {
      if (await source.writeBoxart(c.platform, c.stem, box.bytes, box.replace) !== 'written') throw new Error('conflict');
      boxWritten(c, box.bytes);
      acknowledge(c, BOX_FIELDS);
    } catch (error) { ok = false; failed('box', error); }
  } else if (source.removeBoxart && boxRemove(c)) {
    wrote = true;
    try {
      if (await source.removeBoxart(c.platform, c.stem) !== 'deleted') throw new Error('conflict');
      boxRemoved(c);
      source.boxarts?.delete(`${c.platform}/${c.stem.normalize('NFC')}`);
      acknowledge(c, BOX_FIELDS);
    } catch (error) { ok = false; failed('box', error); }
  }
  if (!wrote) return false;
  if (ok) visit(c);
  return ok;
}
