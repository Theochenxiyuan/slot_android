// One saved baseline per cart; downloaded assets remain available when edits are reset.
export const LABEL_FIELDS = ['game', 'matchedCrc', 'rejected', 'choice', 'byHand', 'customLogoBytes', 'customLogoLuma',
  'customLabelBytes', 'logoBytes', 'logoLuma', 'look', 'boxHue', 'deep', 'userHue'];
export const BOX_FIELDS = ['boxChoice', 'customBoxBytes', 'boxBytes'];
const EDITABLE = [...LABEL_FIELDS, ...BOX_FIELDS, 'shell'];
const capture = (c) => Object.fromEntries(EDITABLE.map((k) => [k, c[k]]));
const eq = (a, b) => a === b || (Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((v, i) => v === b[i]));
// Asset reads are not edits. A new match or an explicit choice still participates in the baseline.
const INPUTS = ['game', 'rejected', 'choice', 'byHand', 'customLogoBytes', 'customLabelBytes',
  'boxChoice', 'customBoxBytes', 'deep', 'userHue', 'shell'];
export const visited = (c) => !!c.baseline;
export function visit(c) { if (!c.reading) c.baseline = capture(c); }
export function commit(c, change) {
  if (!visited(c)) c.baseline = capture(c);
  const before = capture(c);
  change();
  return EDITABLE.some((k) => !eq(before[k], c[k]));
}
export const canRevert = (c) => !!c.baseline && INPUTS.some((k) => !eq(c.baseline[k], c[k]));
export function revert(c) {
  if (c.reading || !canRevert(c)) return false;
  Object.assign(c, c.baseline);
  return true;
}
export function amend(c, fix) { if (c.baseline) fix(c.baseline); }
export function acknowledge(c, fields) {
  if (c.baseline) for (const key of fields) c.baseline[key] = c[key];
}
