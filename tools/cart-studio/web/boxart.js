// Box art: the picture a cart shows once it is seated on the handheld. It is chosen and written
// beside the label, never instead of it, and a cart with none simply keeps its label there.

export const BOX_KINDS = ['card', 'fetched', 'custom'];
export const BOX_EDGE = 512;
const MAX_SOURCE = 32 * 1024 * 1024;

export function boxAvailable(c, kind) {
  if (kind === 'card') return !!c.boxOnCard;
  if (kind === 'fetched') return !!c.boxBytes && !!c.game && !c.rejected;
  return true;
}

// What the cart wears. A choice with nothing behind it, or none at all, leaves what is on the card
// alone, and fills an empty place with the fetched image.
export function boxWears(c) {
  switch (c.boxChoice) {
    case 'none':
      return 'none';
    case 'custom':
      return c.customBoxBytes ? 'custom' : 'none';
    case 'card':
      return c.boxOnCard ? 'card' : 'none';
    case 'fetched':
      if (boxAvailable(c, 'fetched')) return 'fetched';
      break;
    default:
  }
  if (c.boxOnCard) return 'card';
  return boxAvailable(c, 'fetched') ? 'fetched' : 'none';
}

export function boxRemove(c) {
  return !c.error && !!c.boxOnCard && c.boxChoice === 'none';
}

export function boxRemoved(c) {
  c.boxOnCard = false;
  c.boxCardBytes = null;
  c.boxChoice = 'none';
}

export function boxBytesOf(c, kind = boxWears(c)) {
  if (kind === 'card') return c.boxCardBytes ?? null;
  if (kind === 'fetched') return c.boxBytes ?? null;
  if (kind === 'custom') return c.customBoxBytes ?? null;
  return null;
}

// What Save has to write for this cart, or null. What is on the card is replaced only when the
// person chose another image for it.
export function boxWrite(c) {
  if (c.error) return null;
  const kind = boxWears(c);
  if (kind !== 'fetched' && kind !== 'custom') return null;
  const bytes = boxBytesOf(c, kind);
  return bytes ? { bytes, replace: !!c.boxOnCard } : null;
}

export const chooseBox = (c, kind) => {
  c.boxChoice = kind;
};

export function useCustomBox(c, bytes) {
  c.customBoxBytes = bytes;
  c.boxChoice = 'custom';
}

// The handheld took the image.
export function boxWritten(c, bytes) {
  c.boxOnCard = true;
  c.boxCardBytes = bytes;
  c.boxChoice = null;
}

// One turned up on the handheld while saving: it stays, and the choice is open again.
export function boxTurnedUp(c) {
  c.boxOnCard = true;
  c.boxCardBytes = null;
  c.boxChoice = null;
}

// The largest centred square of a picture, and the size it is saved at.
export function squareCrop(width, height, edge = BOX_EDGE) {
  const side = Math.min(width, height);
  return { sx: Math.floor((width - side) / 2), sy: Math.floor((height - side) / 2), side, out: Math.min(side, edge) };
}

// A picture of the person's own, as the square PNG the handheld keeps; null when it can't be read.
export async function readBoxArt(file) {
  if (!file || file.size > MAX_SOURCE) return null;
  let bitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    return null;
  }
  try {
    const { sx, sy, side, out } = squareCrop(bitmap.width, bitmap.height);
    if (side < 1) return null;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = out;
    const g = canvas.getContext('2d');
    g.imageSmoothingQuality = 'high';
    g.drawImage(bitmap, sx, sy, side, side, 0, 0, out, out);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
    return blob ? new Uint8Array(await blob.arrayBuffer()) : null;
  } finally {
    bitmap.close?.();
  }
}

const decoded = new WeakMap();
const PREVIEW = 256;

// Draws box art into a square canvas. A later call for the same canvas wins over one still waiting.
export async function drawBox(canvas, bytes) {
  const turn = (canvas.boxTurn = (canvas.boxTurn ?? 0) + 1);
  let bitmap = decoded.get(bytes);
  if (!bitmap) {
    try {
      bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
    } catch {
      return false;
    }
    decoded.set(bytes, bitmap);
  }
  if (canvas.boxTurn !== turn) return false;
  canvas.width = canvas.height = PREVIEW;
  canvas.getContext('2d').drawImage(bitmap, 0, 0, PREVIEW, PREVIEW);
  return true;
}
