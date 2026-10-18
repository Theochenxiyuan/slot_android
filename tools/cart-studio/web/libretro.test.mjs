import test from 'node:test';
import assert from 'node:assert/strict';
import { fetchDat, fetchIndex, fetchArt, fetchThumb, setArtHeaders } from './libretro.js';

test('artwork and database reads carry an abort deadline, including redirected thumbnails', async () => {
  const original = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url: String(url), options });
    return { ok: true, status: 200, text: async () => '', json: async () => ({}), arrayBuffer: async () => new ArrayBuffer(0) };
  };
  try {
    await fetchDat('GBA');
    await fetchIndex('https://example.com/art/');
    await fetchArt('https://example.com/art/', 'GBA/wheel/test.png');
    let redirected = false;
    await fetchThumb('GBA', 'Named_Boxarts', 'Game', () => { if (!redirected) { redirected = true; return 'Other.png'; } });
    assert.equal(requests.length, 5);
    for (const { options } of requests) {
      assert.ok(options.signal instanceof AbortSignal);
      assert.equal(options.signal.aborted, false);
    }
    assert.deepEqual(requests[1].options.headers, {});
  } finally {
    globalThis.fetch = original;
    setArtHeaders(() => ({}));
  }
});
