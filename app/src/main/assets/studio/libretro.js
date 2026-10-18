// libretro's databases and thumbnails. raw.githubusercontent.com answers cross-origin requests but
// is unreliable from some networks, so each fetch walks a list of url patterns that carry the same
// files and takes the first one that answers. thumbnails.libretro.com serves the same images and
// sends no access-control-allow-origin header at all, so a page can fetch it and still not read a byte.
// {repo} is the only slot a pattern can name; the database fetch names no repo at all.

const DAT_URLS = [
  'https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro/',
  'https://cdn.jsdelivr.net/gh/libretro/libretro-database@master/metadat/no-intro/',
];
const THUMB_URLS = [
  'https://raw.githubusercontent.com/libretro-thumbnails/{repo}/master/',
  'https://cdn.jsdelivr.net/gh/libretro-thumbnails/{repo}@master/',
];
const REQUEST_TIMEOUT = 15_000;

// One database and one thumbnail repository per platform, under libretro's own name for the
// system: spaces in the database file, underscores in the repository. The platform is the card
// folder a rom sits in, so a .gbc filed under GB is looked up in the Game Boy database — slot
// decides a platform by where a file is, and the studio does not second-guess it by extension.
const NAMES = {
  GBA: 'Nintendo - Game Boy Advance',
  GB: 'Nintendo - Game Boy',
  GBC: 'Nintendo - Game Boy Color',
};

// Try each pattern in turn and take the first one that answers. A 404 is a real answer — the file
// is not there on any mirror — so only a failed or refused request moves to the next pattern.
async function fromUrls(patterns, repo, path, ok404 = false) {
  let lastError = null;
  for (const pattern of patterns) {
    try {
      const r = await fetch(`${pattern.replace('{repo}', repo)}${path}`, {
        signal: AbortSignal.timeout(REQUEST_TIMEOUT),
      });
      if (r.status === 404 && ok404) return null;
      if (r.status === 404) continue;
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return r;
    } catch (e) {
      lastError = e;
    }
  }
  if (lastError) throw lastError;
  return null;
}

export async function fetchDat(platform) {
  const r = await fromUrls(DAT_URLS, '', `${encodeURIComponent(NAMES[platform])}.dat`);
  if (!r) throw new Error('HTTP 404');
  return r.text();
}

// A card can hold hundreds of carts, each wanting two images, and GitHub is not a CDN.
export function limiter(slots) {
  let running = 0;
  const waiting = [];
  const pump = () => {
    while (running < slots && waiting.length) {
      const { job, resolve, reject } = waiting.shift();
      running++;
      job()
        .then(resolve, reject)
        .finally(() => {
          running--;
          pump();
        });
    }
  };
  return (job) =>
    new Promise((resolve, reject) => {
      waiting.push({ job, resolve, reject });
      pump();
    });
}

// A set of our own, hosted as static files: <platform>/<media>/<CRC32>.png, with an index.json
// mapping a rom's checksum to what it holds. Looked up by checksum rather than by name, so it
// needs no agreement about spelling and reaches roms No-Intro has never heard of. It is a
// separate origin from the page, so whatever serves it has to allow the read. On the handheld
// its proxy wants the pairing code, which the page hands over here.
let artHeaders = () => ({});
export function setArtHeaders(headers) {
  artHeaders = headers;
}
// Only the page's own origin ever sees the pairing code.
const artFetch = (url) => fetch(url, {
  headers: url.origin === globalThis.location?.origin ? artHeaders() : {},
  signal: AbortSignal.timeout(REQUEST_TIMEOUT),
});

export async function fetchIndex(base) {
  const r = await artFetch(new URL('index.json', base));
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

export async function fetchArt(base, path) {
  const r = await artFetch(new URL(path, base));
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return new Uint8Array(await r.arrayBuffer());
}

// A thumbnail's bytes, or null when libretro has none. A git symlink comes back as a one-line
// body naming its target in the same folder; `stubTarget`, from the Rust side, tells the two apart.
export async function fetchThumb(platform, folder, name, stubTarget) {
  const repo = NAMES[platform].replaceAll(' ', '_');
  const get = async (file) => {
    const r = await fromUrls(THUMB_URLS, repo, `${folder}/${encodeURIComponent(file)}`, true);
    if (!r) return null;
    return new Uint8Array(await r.arrayBuffer());
  };
  const body = await get(`${name}.png`);
  if (!body) return null;
  const target = stubTarget(body);
  return target === undefined ? body : get(target);
}
