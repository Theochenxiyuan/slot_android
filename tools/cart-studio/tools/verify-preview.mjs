// Run after rebuilding WASM. References are the same Rust pixels checked on Android devices.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const repo = path.resolve(import.meta.dirname, '../../..');
const pkg = path.join(repo, 'app/src/main/assets/studio/pkg');
const wasm = await import(pathToFileURL(path.join(pkg, 'slot_cart_studio.js')));
wasm.initSync({ module: fs.readFileSync(path.join(pkg, 'slot_cart_studio_bg.wasm')) });

function reference(name) {
  const bytes = fs.readFileSync(path.join(repo, `app/src/androidTest/assets/cartridges/${name}.argb`));
  const w = bytes.readUInt32BE(0), h = bytes.readUInt32BE(4);
  const rgba = new Uint8Array(w * h * 4);
  for (let i = 0; i < rgba.length; i += 4) {
    rgba[i] = bytes[i + 9]; rgba[i + 1] = bytes[i + 10];
    rgba[i + 2] = bytes[i + 11]; rgba[i + 3] = bytes[i + 8];
  }
  return { w, h, rgba };
}

// Independent floating-point source-over; compare over black and white to exercise alpha.
function over(dst, src) {
  for (let i = 0; i < dst.length; i += 4) {
    const sa = src[i + 3] / 255, da = dst[i + 3] / 255;
    const a = sa + da * (1 - sa);
    for (let c = 0; c < 3; c++) {
      dst[i + c] = a ? Math.round((src[i + c] * sa + dst[i + c] * da * (1 - sa)) / a) : 0;
    }
    dst[i + 3] = Math.round(a * 255);
  }
}

const cases = [
  ['gba-solid', 'GBA', 'auto c4ab59 solid', [48, 50, 264, 136]],
  ['gba-clear', 'GBA', 'auto 575c87 clear', [48, 50, 264, 136]],
  ['gba-glitter', 'GBA', 'auto 86b9bf glitter', [48, 50, 264, 136]],
  ['gb-solid', 'GB', 'notched 9a978f solid', [46, 98, 220, 195]],
  ['gb-clear', 'GB', 'notched 7c7a8a clear', [46, 98, 220, 195]],
  ['gbc-clear', 'GBC', 'rounded 7c7a8a clear', [45, 110, 223, 199]],
  ['gbc-glitter', 'GBC', 'rounded 86b9bf glitter', [45, 110, 223, 199]],
];
for (const [name, platform, shell, rect] of cases) {
  const { w, h, rgba: face } = reference(name);
  assert.deepEqual([...wasm.cart_size(platform)], [w, h]);
  const actual = wasm.existing_face(new Uint8Array(), platform, '', new Uint8Array(0x150), shell, 'Probe');
  assert.equal(actual.length, w * h * 4);
  let expected = face;
  let board;
  if (!name.endsWith('solid')) {
    const prefix = name.split('-')[0];
    expected = reference(`${prefix}-interior`).rgba;
    board = reference(`${prefix}-backing`).rgba;
    over(expected, board);
    over(expected, face);
  }
  for (const ground of [0, 255]) {
    const a = new Uint8Array(actual.length).fill(ground);
    const b = new Uint8Array(actual.length).fill(ground);
    for (let i = 3; i < a.length; i += 4) a[i] = b[i] = 255;
    over(a, actual); over(b, expected);
    let max = 0;
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      // Generated titles and supplied label artwork are checked by the editor/label tests.
      if (x >= rect[0] - 1 && x <= rect[0] + rect[2] + 1
          && y >= rect[1] - 1 && y <= rect[1] + rect[3] + 1) continue;
      const i = (y * w + x) * 4;
      // Existing interior goldens use the clear shell colour. Glitter front pixels are
      // checked where the opaque PCB covers that different back-plastic tint.
      if (name.endsWith('glitter') && board[i + 3] !== 255) continue;
      for (let c = 0; c < 4; c++) max = Math.max(max, Math.abs(a[i + c] - b[i + c]));
    }
    assert.ok(max <= 1, `${name} on ${ground}: channel error ${max}`);
  }
  console.log(`PASS ${name}: shell/interior/PCB match Android references on black and white`);
}
console.log(`OK (${cases.length} previews)`);
