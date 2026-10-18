// The handheld as the studio's card. The page and its API share one origin on the device; every
// API call carries the pairing code, which lives only in this tab.
import { labelKey } from './card.js';

const PIN_KEY = 'slot-pin';
const HEAD = 0x150;
const MAX_ROM = 64 * 1024 * 1024;
const PLATFORM_OF = { gba: 'GBA', gb: 'GB', gbc: 'GBC' };

export class DeviceError extends Error {
  constructor(code, status) {
    super(code);
    this.code = code;
    this.status = status;
  }
}

export function storedPin() {
  const fromUrl = new URLSearchParams(location.search).get('pin');
  if (fromUrl) {
    sessionStorage.setItem(PIN_KEY, fromUrl.trim().toUpperCase());
    const url = new URL(location.href);
    url.searchParams.delete('pin');
    history.replaceState(null, '', url);
  }
  return sessionStorage.getItem(PIN_KEY);
}

export const rememberPin = (pin) => sessionStorage.setItem(PIN_KEY, pin);
export const forgetPin = () => sessionStorage.removeItem(PIN_KEY);
export const authHeaders = () => ({ Authorization: `Bearer ${sessionStorage.getItem(PIN_KEY) ?? ''}` });

let onPinInvalid = null;
export function setPinHandler(handler) {
  onPinInvalid = handler;
}

const segment = (text) => encodeURIComponent(text);

async function call(path, init = {}, retried = false) {
  let response;
  try {
    response = await fetch(path, { ...init, headers: { ...authHeaders(), ...(init.headers ?? {}) } });
  } catch {
    throw new DeviceError('offline', 0);
  }
  if (response.ok) return response;
  if (response.status === 401 && !retried && onPinInvalid && (await onPinInvalid())) {
    return call(path, init, true);
  }
  let code = 'http';
  try {
    code = (await response.json()).error ?? code;
  } catch {
    // Not every failure has a body.
  }
  throw new DeviceError(code, response.status);
}

export async function library() {
  return (await call('api/library')).json();
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Turns a listing into the same shape fromDirectory gives, with the reads done on the device.
export function fromDevice(listing) {
  const labels = new Map();
  const addLabels = (list) => {
    for (const { platform, stem } of list) {
      const key = labelKey(platform, stem);
      if (!labels.has(key)) {
        labels.set(key, async () => (await call(`api/labels/${platform}/${segment(`${stem}.png`)}`)).blob());
      }
    }
  };
  addLabels(listing.labels);
  // Box art is listed and read the same way, under its own folder; an older handheld lists none.
  const boxarts = new Map();
  const addBoxarts = (list = []) => {
    for (const { platform, stem } of list) {
      const key = labelKey(platform, stem);
      if (!boxarts.has(key)) {
        boxarts.set(key, async () => (await call(`api/boxart/${platform}/${segment(`${stem}.png`)}`)).blob());
      }
    }
  };
  addBoxarts(listing.boxarts);
  let version = 'none';
  const romPath = (c) => `api/games/${c.platform}/${segment(c.file)}`;
  return {
    carts: listing.carts.map(({ platform, stem, file }) => ({ platform, stem, file })),
    labels,
    addLabels,
    boxarts,
    addBoxarts,
    direct: true,
    async remove(c) {
      await call(romPath(c), { method: 'DELETE' });
    },
    async head(c) {
      return new Uint8Array(await (await call(`${romPath(c)}/head`)).arrayBuffer()).slice(0, HEAD);
    },
    async crc(c) {
      for (let wait = 300; ; wait = Math.min(wait * 1.5, 2000)) {
        const response = await call(`${romPath(c)}/crc`);
        if (response.status === 200) return (await response.json()).crc >>> 0;
        await sleep(wait);
      }
    },
    // Match the browser-card contract; the device endpoint returns both layers atomically.
    async systemShells() {
      const body = await (await call('api/shells')).json();
      version = body.version;
      return body.system;
    },
    async labelShells() {
      const body = await (await call('api/shells')).json();
      version = body.version;
      return body.labels;
    },
    async writeShells(text) {
      const body = await (await call(`api/shells?version=${segment(version)}`, { method: 'PUT', body: text })).json();
      version = body.version;
    },
    async write(platform, stem, bytes, replace = false) {
      const path = `api/labels/${platform}/${segment(`${stem}.png`)}${replace ? '?replace=1' : ''}`;
      return (await (await call(path, { method: 'PUT', body: bytes })).json()).result;
    },
    async removeLabel(platform, stem) {
      return (await (await call(`api/labels/${platform}/${segment(`${stem}.png`)}`, { method: 'DELETE' })).json()).result;
    },
    async writeBoxart(platform, stem, bytes, replace = false) {
      const path = `api/boxart/${platform}/${segment(`${stem}.png`)}${replace ? '?replace=1' : ''}`;
      return (await (await call(path, { method: 'PUT', body: bytes })).json()).result;
    },
    async removeBoxart(platform, stem) {
      return (await (await call(`api/boxart/${platform}/${segment(`${stem}.png`)}`, { method: 'DELETE' })).json()).result;
    },
  };
}

// Which shelf a dropped file goes to, by extension as the handheld's own import decides it.
export function platformOf(name) {
  const dot = name.lastIndexOf('.');
  return dot > 0 && !name.startsWith('.') ? PLATFORM_OF[name.slice(dot + 1).toLowerCase()] ?? null : null;
}

export function checkRom(file) {
  if (!platformOf(file.name)) return 'format';
  if (file.size > MAX_ROM) return 'size';
  if (file.size < HEAD) return 'incomplete';
  return null;
}

// One file at a time; `progress` gets the fraction sent so far.
export async function upload(file, progress, retried = false) {
  const outcome = await new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', `api/import/${platformOf(file.name)}/${segment(file.name)}`);
    for (const [k, v] of Object.entries(authHeaders())) xhr.setRequestHeader(k, v);
    xhr.upload.onprogress = (e) => e.lengthComputable && progress(e.loaded / e.total);
    xhr.onload = () => {
      let body = {};
      try {
        body = JSON.parse(xhr.responseText);
      } catch {
        // Fall through to the status.
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve({ result: body.result });
      else resolve({ error: body.error ?? (xhr.status === 401 ? 'pin' : 'http'), status: xhr.status });
    };
    xhr.onerror = () => resolve({ error: 'offline' });
    xhr.send(file);
  });
  if (outcome.status === 401 && !retried && onPinInvalid && (await onPinInvalid())) {
    return upload(file, progress, true);
  }
  return outcome;
}
