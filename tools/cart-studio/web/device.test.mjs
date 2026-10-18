import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

const store = new Map();
globalThis.sessionStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
globalThis.location = new URL('http://192.168.1.5:1092/?pin=k7m2qd&art=x');
globalThis.history = { replaceState: (_, __, url) => (globalThis.location = new URL(url)) };

const { fromDevice, storedPin, rememberPin, setPinHandler, checkRom, platformOf, DeviceError } = await import('./device.js');

let calls = [];
let answers = [];
globalThis.fetch = async (url, init = {}) => {
  calls.push({ url, method: init.method ?? 'GET', auth: init.headers?.Authorization, body: init.body });
  const next = answers.shift();
  return next instanceof Response ? next : new Response(JSON.stringify(next ?? {}), { status: 200 });
};
const json = (body, status = 200) => new Response(JSON.stringify(body), { status });

beforeEach(() => {
  calls = [];
  answers = [];
});

const LISTING = {
  carts: [
    { platform: 'GBA', file: 'Game + Bonus (USA).gba', stem: 'Game + Bonus (USA)', size: 512 },
    { platform: 'GB', file: 'Cafe\u0301.gb', stem: 'Cafe\u0301', size: 512 },
  ],
  labels: [{ platform: 'GB', stem: 'Café' }],
};

test('a pairing code from the QR address is kept for the tab and taken off the address', () => {
  assert.equal(storedPin(), 'K7M2QD');
  assert.equal(location.search, '?art=x');
  assert.equal(storedPin(), 'K7M2QD');
});

test('the device source has the shape fromDirectory gives', async () => {
  const source = fromDevice(LISTING);
  assert.equal(source.direct, true);
  assert.deepEqual(source.carts.map((c) => c.stem), ['Game + Bonus (USA)', 'Cafe\u0301']);
  assert.equal(typeof source.systemShells, 'function');
  assert.equal(typeof source.labelShells, 'function');
  assert.equal(typeof source.writeShells, 'function');
  assert.equal(typeof source.write, 'function');
  // Labels are found by the composed key, whatever spelling either side used.
  const label = source.labels.get('GB/Café');
  assert.ok(label);
  answers.push(new Response(new Uint8Array([137, 80, 78, 71]), { status: 200 }));
  const blob = await label();
  assert.equal((await blob.arrayBuffer()).byteLength, 4);
  assert.equal(calls[0].url, 'api/labels/GB/Caf%C3%A9.png');
  assert.equal(calls[0].auth, 'Bearer K7M2QD');
});

test('head and checksum are asked of the device, never the whole rom', async () => {
  const source = fromDevice(LISTING);
  const cart = source.carts[0];
  answers.push(new Response(new Uint8Array(0x150).fill(7), { status: 200 }));
  const head = await source.head(cart);
  assert.equal(head.length, 0x150);
  assert.equal(calls[0].url, 'api/games/GBA/Game%20%2B%20Bonus%20(USA).gba/head');
  answers.push(json({ pending: true }, 202), json({ crc: 4294967295 }));
  assert.equal(await source.crc(cart), 4294967295);
  assert.deepEqual(calls.slice(1).map((c) => c.url), [
    'api/games/GBA/Game%20%2B%20Bonus%20(USA).gba/crc',
    'api/games/GBA/Game%20%2B%20Bonus%20(USA).gba/crc',
  ]);
});

test('shell reads and writes carry the version they were read at', async () => {
  const source = fromDevice(LISTING);
  answers.push(json({ system: 'A = notched 445566 clear\n', labels: '', version: 'none' }));
  assert.equal(await source.systemShells(), 'A = notched 445566 clear\n');
  answers.push(json({ system: '', labels: 'B = auto\n', version: 'none' }));
  assert.equal(await source.labelShells(), 'B = auto\n');
  answers.push(json({ version: 'abc' }));
  await source.writeShells('A = auto\n');
  assert.equal(calls[2].url, 'api/shells?version=none');
  assert.equal(calls[2].method, 'PUT');
  assert.equal(calls[2].body, 'A = auto\n');
  answers.push(json({ error: 'conflict' }, 409));
  await assert.rejects(source.writeShells('A = auto\n'), (e) => e instanceof DeviceError && e.status === 409);
  assert.equal(calls[3].url, 'api/shells?version=abc');
});

test('a label write keeps what is there unless told to replace it', async () => {
  const source = fromDevice(LISTING);
  answers.push(json({ result: 'skipped' }), json({ result: 'written' }));
  assert.equal(await source.write('GBA', 'Game + Bonus (USA)', new Uint8Array([1])), 'skipped');
  assert.equal(await source.write('GBA', 'Game + Bonus (USA)', new Uint8Array([1]), true), 'written');
  assert.equal(calls[0].url, 'api/labels/GBA/Game%20%2B%20Bonus%20(USA).png');
  assert.equal(calls[1].url, 'api/labels/GBA/Game%20%2B%20Bonus%20(USA).png?replace=1');
});

test('box art is listed, read and written under its own path, beside the labels', async () => {
  const source = fromDevice({ ...LISTING, boxarts: [{ platform: 'GBA', stem: 'Game + Bonus (USA)' }] });
  assert.ok(source.boxarts.has('GBA/Game + Bonus (USA)'));
  assert.equal(source.boxarts.has('GB/Café'), false);
  answers.push(new Response(new Uint8Array([137, 80, 78, 71]), { status: 200 }));
  await source.boxarts.get('GBA/Game + Bonus (USA)')();
  assert.equal(calls[0].url, 'api/boxart/GBA/Game%20%2B%20Bonus%20(USA).png');
  answers.push(json({ result: 'skipped' }), json({ result: 'written' }));
  assert.equal(await source.writeBoxart('GBA', 'Game + Bonus (USA)', new Uint8Array([1])), 'skipped');
  assert.equal(await source.writeBoxart('GBA', 'Game + Bonus (USA)', new Uint8Array([1]), true), 'written');
  assert.equal(calls[1].url, 'api/boxart/GBA/Game%20%2B%20Bonus%20(USA).png');
  assert.equal(calls[2].url, 'api/boxart/GBA/Game%20%2B%20Bonus%20(USA).png?replace=1');
  assert.equal(calls[2].method, 'PUT');
  source.addBoxarts([{ platform: 'GB', stem: 'Cafe\u0301' }]);
  assert.ok(source.boxarts.has('GB/Café'));
  answers.push(json({ result: 'deleted' }), json({ result: 'deleted' }));
  assert.equal(await source.removeLabel('GBA', 'Game + Bonus (USA)'), 'deleted');
  assert.equal(await source.removeBoxart('GBA', 'Game + Bonus (USA)'), 'deleted');
  assert.equal(calls[3].url, 'api/labels/GBA/Game%20%2B%20Bonus%20(USA).png');
  assert.equal(calls[3].method, 'DELETE');
  assert.equal(calls[4].url, 'api/boxart/GBA/Game%20%2B%20Bonus%20(USA).png');
  assert.equal(calls[4].method, 'DELETE');
});

test('a handheld that lists no box art leaves the map empty', () => {
  assert.equal(fromDevice(LISTING).boxarts.size, 0);
});

test('a wrong code reads as a pin failure', async () => {
  setPinHandler(null);
  const source = fromDevice(LISTING);
  answers.push(json({ error: 'pin' }, 401));
  await assert.rejects(source.systemShells(), (e) => e.code === 'pin' && e.status === 401);
});

test('a stale code can be refreshed in place and retries the interrupted request once', async () => {
  const source = fromDevice(LISTING);
  setPinHandler(async () => {
    rememberPin('N9P4R8');
    return true;
  });
  answers.push(json({ error: 'pin' }, 401), json({ system: '', labels: 'A = auto\n', version: 'v2' }));
  assert.equal(await source.labelShells(), 'A = auto\n');
  assert.equal(calls.length, 2);
  assert.equal(calls[0].auth, 'Bearer K7M2QD');
  assert.equal(calls[1].auth, 'Bearer N9P4R8');
  setPinHandler(null);
  rememberPin('K7M2QD');
});

test('deletion is paired and names the exact ROM, with no rename operation exposed', async () => {
  const source = fromDevice(LISTING);
  assert.equal(source.rename, undefined);
  await source.remove(source.carts[0]);
  assert.equal(calls[0].url, 'api/games/GBA/Game%20%2B%20Bonus%20(USA).gba');
  assert.equal(calls[0].method, 'DELETE');
  assert.equal(calls[0].auth, 'Bearer K7M2QD');
  answers.push(json({ error: 'not_found' }, 404));
  await assert.rejects(source.remove(source.carts[0]), (e) => e.status === 404);
});

test('uploads go to the shelf their extension names and are checked before sending', () => {
  assert.equal(platformOf('A.GBA'), 'GBA');
  assert.equal(platformOf('B.gb'), 'GB');
  assert.equal(platformOf('C.gbc'), 'GBC');
  assert.equal(platformOf('.hidden.gba'), null);
  assert.equal(platformOf('D.zip'), null);
  assert.equal(checkRom({ name: 'A.gba', size: 0x150 }), null);
  assert.equal(checkRom({ name: 'A.gba', size: 0x14f }), 'incomplete');
  assert.equal(checkRom({ name: 'A.gba', size: 64 * 1024 * 1024 + 1 }), 'size');
  assert.equal(checkRom({ name: 'A.nds', size: 1024 }), 'format');
});
