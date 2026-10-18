// Conservative fallback artwork matching for translated, patched and homebrew ROMs.

const PLATFORMS = new Set(['GBA', 'GB', 'GBC']);

const REGION_NAMES = new Set([
  'USA',
  'Europe',
  'Japan',
  'World',
  'Australia',
  'Canada',
  'China',
  'France',
  'Germany',
  'Hong Kong',
  'Italy',
  'Korea',
  'Mexico',
  'Netherlands',
  'Russia',
  'Scandinavia',
  'Spain',
  'Sweden',
  'Taiwan',
  'UK',
  'United Kingdom',
  'Asia',
  'Brazil',
]);

const KNOWN_ALIASES = new Map([
  ['for the frog the bell tolls', 'kaeru no tame ni kane wa naru'],
  ['mother 3', 'mother 3'],
]);

const DIGITAL_EDITIONS = new Map([
  ['virtual console', 'virtual console'],
  ['switch online', 'switch online'],
  ['evercade', 'evercade'],
  ['collection of mana', 'collection of mana'],
  ['seiken densetsu collection', 'seiken densetsu collection'],
  ['castlevania advance collection', 'castlevania advance collection'],
  ['mega man battle network legacy collection', 'mega man battle network legacy collection'],
  ['contra anniversary collection', 'contra anniversary collection'],
  ['collection of saga', 'collection of saga'],
  ['namco museum', 'namco museum'],
  ['atari 50', 'atari 50'],
  ['qubyte classics', 'qubyte classics'],
]);

const SERIAL_RE = /^[A-Z0-9]{4}$/;
const REVISION_RE = /^(?:rev|revision)\s*([0-9a-z]+)$/i;
const VERSION_RE = /^v(\d+(?:\.\d+)*)[a-z]?$/i;

function untaggedPrefix(text) {
  if (typeof text !== 'string') return '';
  const open = text.search(/[([\uFF08\u3010]/);
  return (open === -1 ? text : text.slice(0, open)).trim();
}

export function normalizeTitle(text) {
  const prefix = untaggedPrefix(text);
  if (!prefix) return '';
  return prefix
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/['’`]/g, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

export function extractBracketTags(text) {
  if (typeof text !== 'string') return [];
  const tags = [];
  const re = /\(([^()]*)\)|\[([^\[\]]*)\]/g;
  let match;
  while ((match = re.exec(text)) !== null) {
    const inner = (match[1] ?? match[2] ?? '').trim();
    if (inner) tags.push(inner);
  }
  return tags;
}

const SHORT_REGION_MAP = new Map([
  ['US', ['USA']],
  ['U', ['USA']],
  ['EU', ['Europe']],
  ['E', ['Europe']],
  ['JP', ['Japan']],
  ['J', ['Japan']],
  ['UE', ['USA', 'Europe']],
  ['JU', ['Japan', 'USA']],
  ['JE', ['Japan', 'Europe']],
  ['JUE', ['Japan', 'USA', 'Europe']],
]);

function canonicalRegions(token) {
  const trimmed = token.trim();
  if (trimmed === 'United Kingdom') return ['UK'];
  if (REGION_NAMES.has(trimmed)) return [trimmed];
  return SHORT_REGION_MAP.get(trimmed) ?? null;
}

export function parseTags(text) {
  const rawTags = extractBracketTags(text);
  const regions = [];
  let revision = null;
  let version = null;
  const digitalEditions = [];

  for (const group of rawTags) {
    const parts = group.split(',').map((s) => s.trim()).filter(Boolean);
    if (parts.length > 0 && parts.every((p) => canonicalRegions(p) !== null)) {
      for (const p of parts) {
        for (const r of canonicalRegions(p) ?? []) {
          if (!regions.includes(r)) regions.push(r);
        }
      }
      continue;
    }

    const revMatch = group.match(REVISION_RE);
    if (revMatch && revision === null) {
      revision = revMatch[1].toUpperCase();
      continue;
    }

    const verMatch = group.match(VERSION_RE);
    if (verMatch && version === null) {
      version = verMatch[1].split('.').map((n) => parseInt(n, 10));
      continue;
    }

    const lower = group.toLowerCase();
    for (const [needle, canonical] of DIGITAL_EDITIONS) {
      if (lower === needle || lower.includes(needle)) {
        if (!digitalEditions.includes(canonical)) digitalEditions.push(canonical);
      }
    }
  }

  return { rawTags, regions, revision, version, digitalEditions };
}

export function searchQueryOf(stem) {
  if (typeof stem !== 'string') return '';
  const stripped = stem
    .replace(/\([^()]*\)|\[[^\[\]]*\]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (stripped) return stripped;
  return stem
    .replace(/[()\[\]]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function crcHex(crc) {
  return (crc >>> 0).toString(16).toUpperCase().padStart(8, '0');
}

function unquote(line, key) {
  const prefix = `${key} "`;
  if (!line.startsWith(prefix)) return null;
  const rest = line.slice(prefix.length);
  const end = rest.lastIndexOf('"');
  return end === -1 ? null : rest.slice(0, end);
}

function parseRomLine(line) {
  const crcMatch = line.match(/\bcrc\s+([0-9a-fA-F]{1,8})\b/);
  if (!crcMatch) return null;
  const crc = parseInt(crcMatch[1], 16) >>> 0;
  const serialMatch = line.match(/\bserial\s+"([^"]+)"/);
  return { crc, serial: serialMatch ? serialMatch[1].trim() : null };
}

export function parseCatalog(datText, platform) {
  if (!PLATFORMS.has(platform) || typeof datText !== 'string') {
    return { platform: platform ?? '', entries: [], byTitle: new Map() };
  }

  const entries = [];
  const byTitle = new Map();
  const seenCrc = new Set();

  let inGame = false;
  let name = null;
  let serial = '';

  const flushEntry = (rom) => {
    if (!name || !rom || seenCrc.has(rom.crc)) return;
    seenCrc.add(rom.crc);
    const titleKey = normalizeTitle(name);
    const tags = parseTags(name);
    const rawSerial = (rom.serial || serial || '').trim().toUpperCase();
    const validSerial = SERIAL_RE.test(rawSerial) ? rawSerial : '';
    const entry = {
      name,
      crc: rom.crc,
      crcHex: crcHex(rom.crc),
      serial: validSerial,
      titleKey,
      regions: tags.regions,
      revision: tags.revision,
      version: tags.version,
      digitalEditions: tags.digitalEditions,
    };
    entries.push(entry);
    if (titleKey) {
      let bucket = byTitle.get(titleKey);
      if (!bucket) {
        bucket = [];
        byTitle.set(titleKey, bucket);
      }
      bucket.push(entry);
    }
  };

  for (const rawLine of datText.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!inGame) {
      if (line.startsWith('game (')) {
        inGame = true;
        name = null;
        serial = '';
      }
      continue;
    }
    if (line === ')') {
      inGame = false;
      name = null;
      serial = '';
      continue;
    }
    const parsedName = unquote(line, 'name');
    if (parsedName !== null) {
      name = parsedName;
      continue;
    }
    const parsedSerial = unquote(line, 'serial');
    if (parsedSerial !== null) {
      serial = parsedSerial;
      continue;
    }
    if (line.startsWith('rom (')) {
      flushEntry(parseRomLine(line));
    }
  }

  return { platform, entries, byTitle };
}

function boundedSimilarity(a, b, minRatio = 0.88) {
  if (a === b) return 1;
  const lenA = a.length;
  const lenB = b.length;
  const maxLen = Math.max(lenA, lenB);
  if (maxLen === 0) return 1;
  const maxDist = Math.floor(maxLen * (1 - minRatio));
  if (Math.abs(lenA - lenB) > maxDist) return 0;

  let prev = new Array(lenB + 1);
  let curr = new Array(lenB + 1);
  for (let j = 0; j <= lenB; j++) prev[j] = j;

  for (let i = 1; i <= lenA; i++) {
    curr[0] = i;
    let rowMin = curr[0];
    const chA = a.charCodeAt(i - 1);
    for (let j = 1; j <= lenB; j++) {
      const cost = chA === b.charCodeAt(j - 1) ? 0 : 1;
      const val = Math.min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost);
      curr[j] = val;
      if (val < rowMin) rowMin = val;
    }
    if (rowMin > maxDist) return 0;
    [prev, curr] = [curr, prev];
  }

  const dist = prev[lenB];
  return dist <= maxDist ? 1 - dist / maxLen : 0;
}

function compareVersions(a, b) {
  const len = Math.max(a.length, b.length);
  for (let i = 0; i < len; i++) {
    const va = a[i] ?? 0;
    const vb = b[i] ?? 0;
    if (va !== vb) return va - vb;
  }
  return 0;
}

function sameList(a, b) {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

function artworkSignature(artInfo, crcHexStr) {
  if (!artInfo) return null;
  if (typeof artInfo === 'string') return artInfo;
  if (typeof artInfo === 'object') {
    const tex = artInfo['support-texture'] ?? '';
    const wheel = artInfo.wheel ?? '';
    if (tex || wheel) return `${tex}|${wheel}`;
  }
  return crcHexStr;
}

export function matchFromCatalog(catalog, cart, hasArtwork) {
  if (!catalog || !cart || catalog.platform !== cart.platform) return null;
  if (!Array.isArray(catalog.entries) || catalog.entries.length === 0) return null;

  const rawWanted = normalizeTitle(cart.stem ?? '');
  if (!rawWanted) return null;
  const wanted = KNOWN_ALIASES.get(rawWanted) ?? rawWanted;
  const wantedWords = wanted.split(' ').filter(Boolean).length;

  const cartTags = parseTags(cart.stem ?? '');
  const rawCode = typeof cart.code === 'string' ? cart.code.trim().toUpperCase() : '';
  const cartCode = catalog.platform === 'GBA' && SERIAL_RE.test(rawCode) ? rawCode : '';

  const exactCandidates = catalog.byTitle?.get(wanted) ?? [];
  const pool =
    exactCandidates.length > 0
      ? exactCandidates
      : cartCode && wantedWords >= 2
        ? catalog.entries
        : [];
  if (pool.length === 0) return null;

  const scored = [];

  for (const entry of pool) {
    if (!entry.titleKey) continue;

    if (cartTags.regions.length > 0) {
      if (!entry.regions.some((r) => cartTags.regions.includes(r))) continue;
    }

    if (cartTags.digitalEditions.length > 0) {
      if (!cartTags.digitalEditions.every((d) => entry.digitalEditions.includes(d))) continue;
    }

    if (cartCode && entry.serial) {
      if (cartCode.slice(0, 3) !== entry.serial.slice(0, 3)) continue;
    }

    let score = 0;
    if (entry.titleKey === wanted) {
      score += 100;
    } else {
      if (!cartCode || entry.serial !== cartCode || wantedWords < 2) continue;
      if (entry.titleKey.split(' ').filter(Boolean).length < 2) continue;
      const sim = boundedSimilarity(entry.titleKey, wanted, 0.88);
      if (sim < 0.88) continue;
      score += 60;
    }

    const artInfo = hasArtwork ? hasArtwork(entry.crcHex) : true;
    if (!artInfo) continue;

    if (cartCode && entry.serial) {
      if (cartCode === entry.serial) score += 25;
      else if (cartCode.slice(0, 3) === entry.serial.slice(0, 3)) score += 10;
    }

    if (cartTags.revision !== null) {
      score += entry.revision === cartTags.revision ? 12 : -12;
    } else if (entry.revision === null) {
      score += 5;
    }

    if (cartTags.digitalEditions.length === 0 && entry.digitalEditions.length > 0) {
      score -= 35;
    }

    if (cartTags.version !== null && entry.version !== null) {
      if (compareVersions(cartTags.version, entry.version) === 0) score += 25;
    }

    scored.push({
      entry,
      score,
      sig: artworkSignature(artInfo, entry.crcHex),
    });
  }

  if (scored.length === 0) return null;

  let maxScore = -Infinity;
  for (const item of scored) {
    if (item.score > maxScore) maxScore = item.score;
  }

  const winners = scored.filter((item) => item.score === maxScore);
  if (winners.length === 1) {
    return { game: winners[0].entry.name, crc: winners[0].entry.crc };
  }

  const firstSig = winners[0].sig;
  if (firstSig && winners.every((w) => w.sig === firstSig)) {
    return { game: winners[0].entry.name, crc: winners[0].entry.crc };
  }

  const first = winners[0].entry;
  const allVersionedSameRelease = winners.every(
    ({ entry }) =>
      entry.digitalEditions.length === 0 &&
      entry.titleKey === first.titleKey &&
      entry.revision === first.revision &&
      sameList(entry.regions, first.regions) &&
      Array.isArray(entry.version) &&
      entry.version.length > 0,
  );

  if (allVersionedSameRelease) {
    const sorted = [...winners].sort((a, b) => compareVersions(b.entry.version, a.entry.version));
    if (compareVersions(sorted[0].entry.version, sorted[1].entry.version) > 0) {
      return { game: sorted[0].entry.name, crc: sorted[0].entry.crc };
    }
  }

  return null;
}
