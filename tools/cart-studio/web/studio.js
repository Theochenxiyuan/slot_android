// slot cart studio. Everything that needs a browser lives here: the card, the network and the page.
// Everything that decides what a label looks like is Rust, in pkg/.

import init, {
  Crc32,
  Dat,
  Label,
  auto_shell,
  box_hue,
  cart_shells,
  layered_cart_shells,
  cart_size,
  clean_label,
  existing_face,
  fallback_hue,
  header_code,
  label_tags,
  logo_luma,
  merge_cart_shells,
  shell_key_ok,
  shell_presets,
  stop_colours,
  stub_target,
  thumbnail_name,
} from './pkg/slot_cart_studio.js';
import { fromDirectory, fromFiles, labelKey, PLATFORMS } from './card.js';
import { fromDevice, library as deviceLibrary, storedPin, rememberPin, forgetPin, authHeaders, setPinHandler, platformOf, checkRom, upload as uploadFile } from './device.js';
import { fetchArt, fetchDat, fetchIndex, fetchThumb, limiter, setArtHeaders } from './libretro.js';
import { createEditor } from './editor.js';
import { createGameMenu } from './game-menu.js';
import { amend, canRevert, commit } from './history.js';
import { saveCart } from './save.js';
import { smallPrint } from './smallprint.js';
import { hasLog, logError, logText } from './log.js';
import { applyFill, autoFill, available, fillCounts, fillText, shelfFillBar, wears } from './label-choice.js';
import { boxAvailable, boxBytesOf, boxRemove, boxWears, boxWrite, chooseBox, drawBox, readBoxArt, useCustomBox } from './boxart.js';
import { t, translatePage } from './i18n.js';
import { studioMode, singleListing, studioBack } from './local-mode.js';
import { matchFromCatalog, parseCatalog, searchQueryOf } from './matcher.js';
import { artworkStatus, imageState } from './artwork-state.js';

const mode = studioMode(location.search);
document.body.classList.toggle('local-studio', mode.local);
document.body.classList.toggle('single-cart', mode.single);
const closeNative = () => { if (mode.local && mode.single) window.AndroidStudio?.close(); };

const $ = (id) => document.getElementById(id);
const limit = limiter(4);
// The GBA game code sits at 0xAC and the Game Boy header ends at 0x150; nothing past that is read.
const HEAD = 0x150;
const FATAL = 'The studio ran out of memory or hit an internal error. Reload the page to start again.';

// The box each platform's cart is drawn in, taken from slot at startup. A Game Boy Game Pak is
// the same width as a GBA cart and nearly twice as tall, so a canvas cut for one refuses the
// other's pixels outright rather than drawing them badly.
const faceBox = new Map();
const boxOf = (platform) => faceBox.get(platform) ?? faceBox.get('GBA');
// One parsed No-Intro database per platform, kept across cards: the databases are large and a
// second card of the same platform should not fetch one again.
const dats = new Map();
const datJobs = new Map();
const catalogs = new Map();
let session = null;
let busy = 0;
let writing = false;
// Set by the first WASM trap. What the module holds is undefined after one (a RefCell can stay
// borrowed, and then every face panics), so nothing calls into it again.
let fatal = false;

const track = (promise) => {
  busy++;
  return promise.finally(() => {
    busy--;
  });
};

const cardObserver =
  typeof IntersectionObserver !== 'undefined'
    ? new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            const c = entry.target.__cart;
            if (!c) continue;
            c.visible = entry.isIntersecting;
            if (c.visible && c.needsPaint) schedulePaint(c);
          }
        },
        { rootMargin: '200px 0px' },
      )
    : null;

function newCart({ platform, stem, file }) {
  return {
    // The folder the rom sits in, and so the only thing that says which console it is for.
    platform,
    stem,
    file,
    code: '',
    // A Game Boy pak's header, which picks its shell and plastic. Empty on a GBA cart.
    head: new Uint8Array(0),
    shell: '',
    shellOnCard: '',
    existingPng: null,
    crc: null,
    matchedCrc: null,
    game: null,
    // Logos stay PNG bytes, which are small. The label made from one is not kept: see withLabel.
    logoBytes: null,
    // The real label's ground and band, measured from its scan: sure looks only.
    look: null,
    choice: null,
    byHand: false,
    customLogoBytes: null,
    customLogoLuma: 255,
    customLabelBytes: null,
    // Box art: what the handheld holds, what libretro has for the matched game, and a picture of
    // the person's own. See boxart.js for which of them a cart wears.
    boxOnCard: false,
    boxCardBytes: null,
    boxBytes: null,
    customBoxBytes: null,
    boxChoice: null,
    real: null,
    dressed: false,
    rejected: false,
    boxHue: null,
    // How bright the logo is decides which way the ground goes, so it is read once with the logo
    // and kept. 255 until there is a logo: nothing is drawn before then anyway.
    logoLuma: 255,
    deep: null,
    userHue: false,
    existing: null,
    error: null,
    result: null,
    looking: true,
    reading: true,
    matchState: 'matching',
    artStates: {},
    lookupPending: false,
    visible: !cardObserver,
    needsPaint: false,
    el: null,
  };
}

const realPathOf = (c) => {
  const key = c.rejected ? null : artKey(c);
  return (key && artIndex?.[key]?.['support-texture']) || null;
};

const realOf = (c) => (c.real && c.real.path === realPathOf(c) ? c.real : null);
const realBytesOf = (c) => realOf(c)?.bytes ?? null;

const view = (c) => ({
  choice: c.choice,
  byHand: c.byHand,
  onCard: !!c.existingPng,
  hasReal: !realOf(c)?.failed && !realOf(c)?.missing && !!realPathOf(c),
  hasLogo: !c.rejected && !!c.logoBytes,
  customLogoBytes: c.customLogoBytes,
  customLabelBytes: c.customLabelBytes,
  error: c.error,
});

const wearing = (c) => wears(view(c));

function stateOf(c) {
  if (c.error) return 'error';
  const { kind, why } = wearing(c);
  if (kind === 'card') return 'has-label';
  if ((c.looking && !['customLogo', 'customLabel'].includes(kind)) || (kind === 'real' && !realBytesOf(c))) return 'looking';
  if (kind === 'none') return why === 'unfilled' ? 'unfilled' : 'needs-logo';
  return 'ready';
}

// The box art belongs to the matched game, so its hue only stands while the match does.
const baseHueOf = (c) =>
  c.game && !c.rejected && c.boxHue !== null ? c.boxHue : fallback_hue(c.stem);

// A label is described by its deep corner, whether computed or picked, and the corner a hue
// produces depends on the logo going over it: dark logos get a pale ground, bright ones a deep
// one. stop_colours is that rule, so the page never restates the house numbers itself.
const stopsOf = (c) => stop_colours(baseHueOf(c), lumaOf(c, wearing(c).kind));
const baseDeep = (c) => lookOf(c)?.ground ?? Array.from(stopsOf(c)).slice(0, 3);

const hex = (rgb) => `#${[...rgb].map((v) => v.toString(16).padStart(2, '0')).join('')}`;
// A checksum as the set names its files: eight upper case hex digits.
const crcHex = (crc) => crc.toString(16).toUpperCase().padStart(8, '0');
// Our own set, published beside the studio. `?art=` points at another, a local one while it is
// being harvested, and an empty `?art=` leaves every cart to libretro. On the handheld the set is
// read through its proxy, since the set's host does not answer pages on a local address.
const ART_BASE = new URLSearchParams(location.search).get('art') ?? new URL('art/', location.href).href;
let artIndex = null;
let artReady = Promise.resolve();
let artFailure = null;
const fromHex = (value) => [1, 3, 5].map((at) => parseInt(value.slice(at, at + 2), 16));
// A sure look from the art set, or null: anything missing, unsure or malformed counts as no look.
const rgbOf = (h) => (typeof h === 'string' && /^[0-9a-f]{6}$/i.test(h) ? fromHex(`#${h}`) : null);
function parseLook(raw) {
  if (!raw || raw.sure !== true) return null;
  const ground = rgbOf(raw.ground);
  if (!ground) return null;
  if (!raw.band) return { ground, band: null };
  const { edge, size } = raw.band;
  const colour = rgbOf(raw.band.colour);
  const ok = ['top', 'bottom', 'left', 'right'].includes(edge) && typeof size === 'number' && size > 0 && size < 0.5;
  return ok && colour ? { ground, band: { edge, size, colour } } : null;
}
const lumaOf = (c, kind) => (kind === 'customLogo' ? c.customLogoLuma : c.logoLuma);
const lookOf = (c) => (wearing(c).kind === 'logo' ? c.look : null);
const bandOf = (c) => lookOf(c)?.band ?? null;

// A handheld that cannot take box art keeps working with labels alone.
const wantsBox = (s, c) => !!s.source.writeBoxart && !c.boxOnCard;
// Into the Labels layer.
const mergedShells = (s, carts, values) =>
  merge_cart_shells(
    s.shellText ?? '',
    carts.map((c) => c.stem),
    values.map((v, i) => v || (s.systemShells?.has(carts[i].stem.normalize('NFC')) ? 'auto' : '')),
  );
const SHELL_FILE = 'Labels/cart_shell.txt';

// A Label holds its composition in WASM memory, which never shrinks and stops at 4 GiB.
// One kept per cart runs a big card out of it, so a Label lives only for the call that needs it.
function withLabel(bytes, deep, platform, use, band = null) {
  const label = band
    ? new Label(bytes, deep, platform, band.edge, band.size, new Uint8Array(band.colour))
    : new Label(bytes, deep, platform, '', 0, new Uint8Array(0));
  try {
    return use(label);
  } finally {
    label.free();
  }
}

function withKindLabel(c, kind, use) {
  if (kind === 'real' || kind === 'customLabel') {
    const bytes = kind === 'real' ? realBytesOf(c) : c.customLabelBytes;
    if (!bytes) return null;
    const label = Label.full(bytes, c.platform);
    try {
      return use(label);
    } finally {
      label.free();
    }
  }
  const bytes = kind === 'logo' ? c.logoBytes : kind === 'customLogo' ? c.customLogoBytes : null;
  if (!bytes) return null;
  const look = kind === 'logo' ? c.look : null;
  const deep = c.deep ?? look?.ground ?? Array.from(stop_colours(baseHueOf(c), lumaOf(c, kind))).slice(0, 3);
  return withLabel(bytes, deep, c.platform, use, look?.band ?? null);
}

const withCartLabel = (c, use) => withKindLabel(c, wearing(c).kind, use);

const BLANK = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAD0lEQVR4nGO0snJiYGAAAAOUALgNWObMAAAAAElFTkSuQmCC'), (ch) => ch.charCodeAt(0));

function faceFor(c, kind) {
  if (kind === 'card') return c.existing;
  if (kind === 'none') return existing_face(new Uint8Array(0), c.platform, c.code, c.head, c.shell, c.stem);
  if (kind === 'unfilled') {
    const label = Label.full(BLANK, c.platform);
    try {
      return label.face(c.platform, c.code, c.head, c.shell, c.stem);
    } finally {
      label.free();
    }
  }
  return withKindLabel(c, kind, (label) => label.face(c.platform, c.code, c.head, c.shell, c.stem));
}

// Whether slot's decoder takes a logo, asked the only way the module can be asked: by making one.
function readable(bytes) {
  try {
    // Any platform will do: the question is only whether slot's decoder takes these bytes.
    withLabel(bytes, [0, 0, 0], 'GBA', () => {});
    return true;
  } catch (e) {
    if (e instanceof WebAssembly.RuntimeError) throw e;
    return false;
  }
}

// A WebAssembly.RuntimeError is a trap: memory ran out, or Rust panicked. The plain Error a Label
// throws for bytes that aren't a PNG is not one. Returns whether `e` was a trap.
function trapped(e) {
  if (!(e instanceof WebAssembly.RuntimeError)) return false;
  if (!fatal) {
    fatal = true;
    loading(null);
    logError('trap', e);
    $('progress').hidden = true;
    banner(FATAL);
    editor.refresh();
  }
  return true;
}

// A thumbnail that won't fetch is only a missing image. A trap on the way is not.
const noImage = (e) => {
  if (e instanceof WebAssembly.RuntimeError) throw e;
  return null;
};

function describe(c, state) {
  if (state === 'error') return c.error;
  if (state === 'looking' && !c.looking) return t('Loading the real label…');
  if (state === 'looking') return t(c.crc === null ? 'Reading the ROM…' : 'Looking it up…');
  if (state === 'ready' || labelRemove(c) || c.shell !== c.shellOnCard) return t('Not saved yet');
  if (boxPending(c)) return t('Cover not saved yet');
  return '';
}

function buildCard(c) {
  const root = $('card-tpl').content.firstElementChild.cloneNode(true);
  const q = (selector) => root.querySelector(selector);
  c.el = { root, canvas: q('canvas'), face: q('.face'), stem: q('.stem'), print: q('.print'), game: q('.game'), edit: q('.cart-edit'), more: q('.cart-more') };
  const [boxW, boxH] = boxOf(c.platform);
  c.el.canvas.width = boxW;
  c.el.canvas.height = boxH;
  // The device's own title, with the file it came from on hover.
  c.el.stem.textContent = clean_label(c.stem);
  c.el.stem.title = c.stem;
  // slot keeps `(USA, Europe)` as one tag so it never claims two releases; the small print reads
  // each region on its own.
  c.tags = label_tags(c.stem)
    .flatMap((group) => group.split(','))
    .map((tag) => tag.trim())
    .filter(Boolean);
  c.el.edit.setAttribute('aria-label', t(`Edit ${clean_label(c.stem)}`));
  root.addEventListener('click', () => { if (!writing) { gameMenu.close(); editor.open(c); } });
  c.el.edit.addEventListener('keydown', (e) => {
    if (writing || e.target !== c.el.edit || (e.key !== 'Enter' && e.key !== ' ')) return;
    e.preventDefault();
    gameMenu.close();
    editor.open(c);
  });
  gameMenu.bind(c);
  const isLogoDrag = (e) => {
    const items = [...(e.dataTransfer?.items ?? [])].filter((i) => i.kind === 'file');
    return items.length === 1 && (!items[0].type || items[0].type === 'image/png');
  };
  const isLogoDrop = (e) => {
    const files = [...(e.dataTransfer?.files ?? [])];
    return files.length === 1 && !platformOf(files[0].name);
  };
  // Always taken, so a file dropped on a cart that can't use it doesn't navigate the page away;
  // ROM drops pass through to the page-wide upload handler.
  c.el.face.addEventListener('dragover', (e) => {
    e.preventDefault();
    c.el.face.classList.toggle('over', !mode.local && canLabel(c) && isLogoDrag(e));
  });
  c.el.face.addEventListener('dragleave', () => c.el.face.classList.remove('over'));
  c.el.face.addEventListener('drop', (e) => {
    c.el.face.classList.remove('over');
    if (mode.local || !isLogoDrop(e)) return;
    e.preventDefault();
    if (canLabel(c) && !writing) track(takeLogo(c, e.dataTransfer.files[0]));
  });
  root.__cart = c;
  cardObserver?.observe(root);
  return root;
}

// A slider fires faster than a label recomposes, so paints are coalesced to one per frame.
const queued = new Set();
function schedulePaint(c) {
  if (queued.has(c)) return;
  queued.add(c);
  requestAnimationFrame(() => {
    queued.delete(c);
    paint(c);
  });
}

// The face a cart shows. A needs-logo cart has none of its own, so slot's generated label stands
// in, as it does on the device.
function faceOf(c, state) {
  if (state === 'has-label') return faceFor(c, 'card');
  if (state === 'ready') return faceFor(c, wearing(c).kind);
  if (state === 'needs-logo') return faceFor(c, 'none');
  if (state === 'unfilled') return faceFor(c, 'unfilled');
  return null;
}

function drawFace(canvas, face, platform) {
  const pixels = new Uint8ClampedArray(face.buffer, face.byteOffset, face.byteLength);
  const [boxW, boxH] = boxOf(platform);
  canvas.getContext('2d').putImageData(new ImageData(pixels, boxW, boxH), 0, 0);
}

function paint(c) {
  if (fatal || c.deleted) return;
  if (session && needsReal(c)) track(fetchReal(session, c));
  const state = stateOf(c);
  const { root, canvas, game } = c.el;
  root.dataset.state = state;
  game.textContent = describe(c, state);
  // The game code is read with the ROM, after the card is built.
  c.el.print.textContent = smallPrint(c.platform, c.code, c.tags);
  updateFillButton();
  if (cardObserver && !c.visible && editor.current() !== c) {
    c.needsPaint = true;
    return;
  }
  c.needsPaint = false;
  let face;
  try {
    face = faceOf(c, state);
  } catch (e) {
    if (trapped(e)) return;
    throw e;
  }
  canvas.hidden = !face;
  if (face) drawFace(canvas, face, c.platform);
  if (editor.current() === c) editor.refresh();
}

// A game named by hand is dressed exactly as a matched one, so its logo, box art hue and the
// clash rule all apply. The CRC keeps whatever it said; the name is what the label follows.
function chooseGame(c, name) {
  if (c.game === name && !c.rejected) return;
  c.game = name;
  c.matchedCrc = null;
  c.rejected = false;
  c.real = null;
  c.logoBytes = null;
  c.boxBytes = null;
  c.boxHue = null;
  c.look = null;
  if (!c.choice || c.choice === 'card') {
    c.choice = session?.fill ?? 'real';
    c.byHand = true;
  }
  if (!c.boxChoice || c.boxChoice === 'card') {
    c.boxChoice = 'fetched';
  }
  c.looking = true;
  track(redress(session, c));
}

async function redress(s, c) {
  paint(c);
  try {
    await dress(s, c);
  } catch (e) {
    if (!trapped(e)) throw e;
  }
  if (s !== session) return;
  if (c.existingPng && !view(c).hasReal && !view(c).hasLogo && (c.choice === 'real' || c.choice === 'logo')) {
    c.choice = 'card';
    c.byHand = false;
  }
  c.looking = false;
  c.matchState = 'ready';
  paint(c);
}

const choiceOf = (c) => {
  const [outline, colour, finish] = (c.shell || auto_shell(c.platform, c.code, c.head)).split(' ');
  return { outline, colour, finish };
};

async function readLogo(file) {
  if (!file || file.size > 32 * 1024 * 1024) return null;
  let bytes = null;
  try {
    const raw = new Uint8Array(await file.arrayBuffer());
    if (fatal) return null;
    if (readable(raw)) bytes = raw;
  } catch (e) {
    if (trapped(e)) return null;
  }
  if (!bytes) {
    let bitmap = null;
    try {
      bitmap = await createImageBitmap(file);
      const maxEdge = 1024;
      const scale = Math.min(1, maxEdge / Math.max(1, bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      const g = canvas.getContext('2d');
      g.imageSmoothingQuality = 'high';
      g.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
      const converted = blob ? new Uint8Array(await blob.arrayBuffer()) : null;
      if (converted && !fatal && readable(converted)) bytes = converted;
    } catch (e) {
      if (trapped(e)) return null;
    } finally {
      bitmap?.close?.();
    }
  }
  if (!bytes) banner(`${file.name} is not an image this studio can read`);
  return bytes;
}

// A logo of the person's own decides which way its ground goes the same way a fetched one does.
function useCustomLogo(c, bytes) {
  c.customLogoBytes = bytes;
  c.customLogoLuma = logo_luma(bytes);
  c.choice = 'customLogo';
  c.byHand = true;
  if (!c.userHue) c.deep = null;
}

function useCustomLabel(c, bytes) {
  c.customLabelBytes = bytes;
  c.choice = 'customLabel';
  c.byHand = true;
}

function choose(c, kind) {
  c.choice = kind;
  c.byHand = kind !== 'card';
  if (!c.userHue) c.deep = null;
  if (kind !== 'card' && !c.dressed) ensureTiles(c);
  else if (needsReal(c)) track(fetchReal(session, c));
}

function ensureTiles(c) {
  if (c.error) return;
  ensureBox(c);
  if (!c.dressed && !c.looking && !c.lookupPending) {
    c.lookupPending = true;
    // A saved native label and shell are already usable while optional art is fetched.
    c.looking = !(mode.local && c.existingPng);
    const s = session;
    track(lookUp(s, c).finally(() => {
      c.lookupPending = false;
      if (s === session && !fatal) paint(c);
    }));
    return;
  }
  if (c.dressed && realPathOf(c) && !realOf(c)) track(fetchReal(session, c));
}

// What the editor's box art tiles need and the cart does not keep: the image on the handheld, and
// libretro's again for a cart whose box art was already there.
function ensureBox(c) {
  const s = session;
  if (!s?.source.writeBoxart) return;
  if (c.boxOnCard && !c.boxCardBytes && !c.boxCardLoading) {
    const read = s.source.boxarts.get(labelKey(c.platform, c.stem));
    if (read) {
      c.boxCardLoading = true;
      track(
        (async () => {
          try {
            c.boxCardBytes = new Uint8Array(await (await read()).arrayBuffer());
          } catch (e) {
            console.error(c.stem, e);
          } finally {
            c.boxCardLoading = false;
          }
          if (s === session) paint(c);
        })(),
      );
    }
  }
  if (c.dressed && c.game && !c.rejected && !c.boxBytes && !c.boxFetching && c.boxTried !== c.game) {
    const name = thumbnail_name(c.game);
    const game = c.game;
    c.boxFetching = true;
    c.boxTried = game;
    c.artStates.fetched = 'loading';
    track(
      limit(async () => (s === session && !fatal ? fetchThumb(c.platform, 'Named_Boxarts', name, stub_target) : null))
        .catch((error) => { c.artStates.fetched = 'error'; return noImage(error); })
        .then((bytes) => {
          c.boxFetching = false;
          if (s !== session || fatal) return;
          if (c.game === game && c.artStates.fetched !== 'error') c.artStates.fetched = bytes ? 'ready' : 'empty';
          if (bytes && c.game === game) {
            c.boxBytes = bytes;
            amend(c, (snap) => {
              if (snap.game === game) snap.boxBytes = bytes;
            });
          }
          paint(c);
        }),
    );
  }
}

function fetchReal(s, c) {
  const path = realPathOf(c);
  if (!path) return Promise.resolve();
  if (realOf(c)) return realOf(c).pending;
  const real = { path, bytes: null, failed: false, loading: true, pending: null };
  c.real = real;
  real.pending = (async () => {
    let bytes = null;
    try {
      bytes = await limit(async () => (s === session && !fatal ? fetchArt(ART_BASE, path) : null));
      if (bytes && !readable(bytes)) { bytes = null; real.failed = true; }
    } catch (e) {
      if (trapped(e)) return;
      real.failed = true;
      bytes = null;
    }
    if (s !== session || fatal || realOf(c) !== real) return;
    real.loading = false;
    real.missing = !bytes && !real.failed;
    if (bytes) real.bytes = bytes;
    c.artStates.real = real.failed ? 'error' : bytes ? 'ready' : 'empty';
    paint(c);
  })();
  return real.pending;
}

function retryReal(c) {
  if (realOf(c)?.failed) c.real = null;
}

const needsReal = (c) => wearing(c).kind === 'real' && !realOf(c);

async function takeLogo(c, file) {
  const bytes = await readLogo(file);
  if (bytes && !writing && session?.carts.includes(c)) {
    editor.open(c);
    if (commit(c, () => useCustomLogo(c, bytes))) changed(c);
  }
}

function loading(text) {
  document.body.classList.toggle('loading', !!text);
  $('loader').hidden = !text;
  if (text) $('loader-text').textContent = t(text);
}

function progress(done, total, stem, verb = 'Reading') {
  if (document.body.classList.contains('loading') && stem) loading(`${verb} ${done + 1} of ${total}: ${stem}`);
  $('progress').hidden = done >= total;
  $('bar').max = total;
  $('bar').value = done;
  $('progress-text').textContent = stem ? t(`${verb} ${done + 1} of ${total}: ${stem}`) : '';
}

function banner(text, retry) {
  const b = $('banner');
  b.replaceChildren(Object.assign(document.createElement('span'), { className: 'message', textContent: t(text) }));
  const key = (label, click, extra = '') => {
    const button = Object.assign(document.createElement('button'), {
      type: 'button',
      className: `key dark ${extra}`.trim(),
      textContent: t(label),
    });
    button.addEventListener('click', () => click(button));
    b.append(' ', button);
  };
  if (retry) key('Retry', retry);
  if (hasLog() && !mode.local) {
    key('Copy log', async (button) => {
      try {
        await navigator.clipboard.writeText(logText(navigator.userAgent));
        button.textContent = t('Copied');
      } catch (e) {
        console.error('clipboard', e);
        button.textContent = t('Couldn’t copy');
      }
    }, 'copy-log');
  }
  b.hidden = false;
  // The editor is a modal over the page, so a message raised while it is open is shown inside it.
  if (editor.current()) editor.say(t(text));
}

// A picture for a cart's box art, cropped to the square the handheld keeps.
async function readPicture(file) {
  if (!file) return null;
  const bytes = await readBoxArt(file);
  if (!bytes) banner(`${file.name} is not an image this studio can read`);
  return bytes;
}

async function open(source) {
  if (fatal) {
    banner(FATAL);
    return;
  }
  editor.close();
  loading(null);
  clearUploads();
  for (const c of session?.carts ?? []) cardObserver?.unobserve(c.el.root);
  session = { source, carts: source.carts.map(newCart) };
  $('banner').hidden = true;
  // A card opened mid-scan replaces identify()'s loop before it reaches progress(total, total, ''),
  // so the old bar has to be cleared here rather than left for a call that may never come.
  $('progress').hidden = true;
  $('progress-text').textContent = '';
  $('grid').replaceChildren(...session.carts.map(buildCard));
  session.carts.forEach(paint);
  showPlatform(platformsOf(session)[0] ?? PLATFORMS[0]);
  $('pick').hidden = true;
  $('pick-files-label').hidden = true;
  $('shelf-fill').hidden = true;
  $('fill-open').hidden = true;
  if (session.carts.length === 0) {
    banner(mode.single ? 'This game is no longer available. Reload the page.' : mode.local ? 'No games yet.' : 'No games yet. Upload games to start.');
    track(loadShells(session));
    return;
  }
  track(identify(session));
}

async function crcOf(file) {
  const crc = new Crc32();
  const reader = file.stream().getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return crc.finish();
    crc.update(value);
  }
}

// The handheld answers a ROM's head and checksum itself, so no ROM crosses the network.
const headOf = async (s, c) =>
  s.source.head ? s.source.head(c) : new Uint8Array(await (await c.file()).slice(0, HEAD).arrayBuffer());
const checksumOf = async (s, c) => (s.source.crc ? s.source.crc(c) : crcOf(await c.file()));

async function loadShells(s) {
  const text = { system: '', labels: '' };
  const failed = [];
  try {
    text.system = await s.source.systemShells();
  } catch (e) {
    logError('Config/cart_shell.txt', e);
    failed.push(e);
  }
  try {
    text.labels = await s.source.labelShells();
    s.shellReadFailed = false;
  } catch (e) {
    logError(SHELL_FILE, e);
    s.shellReadFailed = true;
    failed.push(e);
  }
  if (s !== session || fatal) return;
  if (failed.length) banner(t(failed.find((e) => e.name === 'OldShellFile')?.message ?? 'The card’s shells couldn’t be read.'));
  s.shellText = text.labels;
  const system = cart_shells(text.system);
  s.systemShells = new Set(system.filter((_, i) => i % 2 === 0).map((stem) => stem.normalize('NFC')));
  const pairs = layered_cart_shells(text.system, text.labels);
  s.chosen = new Map();
  for (let i = 0; i < pairs.length; i += 2) s.chosen.set(pairs[i].normalize('NFC'), pairs[i + 1]);
}

// A cart whose label is done still needs its checksum, to find its box art by name. Failing to
// read one only costs it the box art.
async function readBoxChecksum(s, c) {
  c.boxFetching = true;
  try {
    c.crc = await checksumOf(s, c);
  } catch (e) {
    c.boxFetching = false;
    if (trapped(e)) throw e;
  }
}

async function readCart(s, c) {
  c.shell = c.shellOnCard = s.chosen?.get(c.stem.normalize('NFC')) ?? '';
  try {
    const head = await headOf(s, c);
    // Only a GBA rom carries a game code. On a Game Boy cart 0xAC is inside the RST vectors,
    // so a code read from there is opcode bytes dressed up as one.
    c.code = c.platform === 'GBA' ? header_code(head) : '';
    if (c.platform !== 'GBA') c.head = head;
    const label = s.source.labels.get(labelKey(c.platform, c.stem));
    c.boxOnCard = !!s.source.boxarts?.has(labelKey(c.platform, c.stem));
    if (label) {
      const bytes = new Uint8Array(await (await label()).arrayBuffer());
      c.existingPng = bytes;
      c.choice = 'card';
      c.existing = existing_face(bytes, c.platform, c.code, c.head, c.shell, c.stem);
      c.looking = false;
      if (!wantsBox(s, c)) c.matchState = 'idle';
      if (wantsBox(s, c)) await readBoxChecksum(s, c);
    } else {
      c.crc = await checksumOf(s, c);
    }
  } catch (e) {
    if (trapped(e)) return false;
    c.error = t(mode.local ? 'Could not read this game. Try again.' : `Could not read this ROM: ${e.message}`);
  } finally {
    c.reading = false;
  }
  paint(c);
  return true;
}

async function identify(s) {
  await loadShells(s);
  if (s !== session || fatal) return;
  const total = s.carts.length;
  for (const [i, c] of s.carts.entries()) {
    if (s !== session || fatal) return;
    progress(i, total, c.stem);
    if (!(await readCart(s, c))) return;
  }
  // A card opened during the last read has its own bar up by now.
  if (s !== session) return;
  progress(total, total, '');
  if (mode.single && s.carts.length === 1) editor.open(s.carts[0]);
  await match(s);
}

// Games sent from this page join the open session, so unsaved choices on the others stay put.
async function extend(s, listing) {
  s.source.addLabels(listing.labels);
  s.source.addBoxarts?.(listing.boxarts);
  const known = new Set(s.carts.map((c) => `${c.platform}/${c.file}`));
  const fresh = listing.carts.filter((c) => !known.has(`${c.platform}/${c.file}`)).map(newCart);
  if (!fresh.length) return;
  if (s.carts.length === 0) $('banner').hidden = true;
  s.carts.push(...fresh);
  $('grid').append(...fresh.map(buildCard));
  for (const c of fresh) c.el.root.hidden = c.platform !== s.platform;
  if (!s.carts.some((c) => c.platform === s.platform)) showPlatform(platformsOf(s)[0]);
  else renderTabs();
  fresh.forEach(paint);
  for (const [i, c] of fresh.entries()) {
    if (s !== session || fatal) return;
    progress(i, fresh.length, c.stem);
    if (!(await readCart(s, c))) return;
  }
  if (s !== session) return;
  progress(fresh.length, fresh.length, '');
  await match(s);
}

async function ensureDat(platform) {
  if (dats.has(platform)) return;
  if (!datJobs.has(platform)) datJobs.set(platform, (async () => {
    const text = await fetchDat(platform);
    dats.set(platform, Dat.parse(text));
    catalogs.set(platform, parseCatalog(text, platform));
  })().finally(() => datJobs.delete(platform)));
  return datJobs.get(platform);
}

function hasCatalogArtwork(hex) {
  const entry = artIndex?.[hex];
  return entry && (entry['support-texture'] || entry.wheel) ? entry : null;
}

function resolveCartGame(c) {
  const exact = dats.get(c.platform)?.game_for(c.crc) ?? null;
  if (exact) return { game: exact, crc: null };
  const catalog = catalogs.get(c.platform);
  if (!catalog) return { game: null, crc: null };
  const hit = matchFromCatalog(catalog, c, hasCatalogArtwork);
  return hit ? { game: hit.game, crc: hit.crc } : { game: null, crc: null };
}

async function match(s) {
  if (s !== session || fatal) return;
  const pending = s.carts.filter((c) => !c.dressed && (!c.existing || wantsBox(s, c) || c.lookupJob) && !c.error);
  await Promise.all(pending.map((c) => lookUp(s, c)));
  if (s === session && !fatal) await fillOrAsk(s);
}

// Where the set files this cart's art: under its own checksum while its game is the one that
// checksum names, else under the first dump of the game chosen for it that the set has.
function artKey(c) {
  const own = c.crc === null ? null : crcHex(c.crc);
  const db = dats.get(c.platform);
  if (!c.game || !db || (c.crc !== null && db.game_for(c.crc) === c.game)) return own;
  if (c.matchedCrc !== null) {
    const matched = crcHex(c.matchedCrc);
    if (artIndex?.[matched]) return matched;
  }
  return Array.from(db.crcs_for(c.game), crcHex).find((k) => artIndex?.[k]) ?? null;
}

// Fetch what `c.game` names and let it decide the cart's hue. The Game row re-runs this for a game
// chosen by hand, so a chosen match is dressed exactly the way a matched one is.
async function dress(s, c) {
  const game = c.game, rejected = c.rejected;
  const current = () => s === session && !fatal && c.game === game && c.rejected === rejected;
  if (!realOf(c)) c.real = null;
  c.matchState = 'loading';
  c.artStates = { real: 'loading', logo: 'loading', fetched: 'loading' };
  paint(c);
  // A set of our own is found by checksum, so a cart no database has a name for can still be
  // dressed from it. libretro is found by name, so those requests only happen when there is one.
  const name = c.game ? thumbnail_name(c.game) : null;
  const key = artKey(c);
  c.look = parseLook(key && artIndex?.[key]?.look);
  // The queue is shared, so a card replaced while its jobs wait gives up their turns without
  // a request instead of making the new card wait behind its downloads.
  const thumb = (folder) =>
    !name
      ? Promise.resolve(null)
      : limit(async () =>
          s === session && !fatal ? fetchThumb(c.platform, folder, name, stub_target) : null,
        ).catch((error) => { if (current()) c.artStates.fetched = 'error'; return noImage(error); });
  // Logos come from our own set alone, by checksum; libretro is asked only for box art.
  const ours = (media) => {
    const path = key && artIndex?.[key]?.[media];
    if (!path) {
      if (artFailure) c.artStates.logo = 'error';
      return Promise.resolve(null);
    }
    return limit(async () =>
      s === session && !fatal ? fetchArt(ART_BASE, path) : null,
    ).catch((error) => { if (current()) c.artStates.logo = 'error'; return noImage(error); });
  };
  // Publish each result as soon as it arrives, independently of the other two downloads.
  const boxJob = thumb('Named_Boxarts').then((box) => {
    if (!current()) return;
    c.boxHue = box ? (box_hue(box) ?? null) : null;
    c.boxBytes = box && (wantsBox(s, c) || editor.current() === c) ? box : null;
    if (c.artStates.fetched !== 'error') c.artStates.fetched = box ? 'ready' : 'empty';
    c.boxTried = box ? null : c.game;
    paint(c);
  });
  const logoJob = (async () => {
    if (!c.logoBytes) {
      const logo = await ours('wheel');
      if (!current()) return;
      c.logoBytes = logo && readable(logo) ? logo : null;
      if (logo && !c.logoBytes) c.artStates.logo = 'error';
      c.logoLuma = c.logoBytes ? logo_luma(c.logoBytes) : 255;
    }
    if (c.artStates.logo !== 'error') c.artStates.logo = c.logoBytes ? 'ready' : 'empty';
    paint(c);
  })();
  c.artStates.real = realPathOf(c) ? 'loading' : artFailure ? 'error' : 'empty';
  const realJob = needsReal(c) || (editor.current() === c && realPathOf(c)) ? fetchReal(s, c) : Promise.resolve();
  await Promise.all([boxJob, logoJob, realJob]);
  if (!current()) {
    if (s === session && !fatal) await dress(s, c);
    return;
  }
  if (!c.userHue && !c.byHand) c.deep = null;
  c.dressed = true;
  c.matchState = 'ready';
  // Downloaded metadata must not make a custom image/shell draft dirty on its own.
  amend(c, (snap) => {
    if (snap.game !== c.game) return;
    for (const key of ['logoBytes', 'logoLuma', 'look', 'boxHue', 'boxBytes']) snap[key] = c[key];
  });
}

// The platforms this card actually holds, in the studio's shelf order.
const platformsOf = (s) => PLATFORMS.filter((p) => s.carts.some((c) => c.platform === p));

// A tab per platform on the card, with what it holds. One platform is no choice at all, so a
// card of nothing but GBA carts shows no switcher and reads exactly as it did before.
const PLATFORM_NAMES = { GBA: 'Game Boy Advance', GB: 'Game Boy', GBC: 'Game Boy Color' };

function renderTabs() {
  const s = session;
  const present = platformsOf(s);
  $('tabs').hidden = present.length < 2;
  $('tabs').replaceChildren(
    ...present.map((p) => {
      const n = s.carts.filter((c) => c.platform === p).length;
      const tab = Object.assign(document.createElement('button'), { type: 'button', className: 'shelf-tab' });
      tab.append(PLATFORM_NAMES[p] ?? p, Object.assign(document.createElement('span'), { className: 'n', textContent: n }));
      tab.setAttribute('aria-pressed', String(p === s.platform));
      tab.addEventListener('click', () => showPlatform(p));
      return tab;
    }),
  );
}

// Switching hides the other shelves rather than rebuilding the grid: a cart keeps its canvas, its
// chosen colour and any logo dropped on it, so coming back to a tab finds it as it was left.
function showPlatform(p) {
  gameMenu.close();
  editor.close();
  session.platform = p;
  for (const c of session.carts) {
    c.el.root.hidden = c.platform !== p;
    if (c.platform === p && c.visible && c.needsPaint) schedulePaint(c);
  }
  renderTabs();
}

const hasLabelFill = (c) => !c.existingPng && (view(c).hasReal || view(c).hasLogo);
const batchCarts = (s) => s.carts.filter((c) => !c.existingPng && !c.byHand && !canRevert(c) && !c.error && !c.looking);
const batchCounts = (s) => fillCounts(batchCarts(s).map((c) => ({ ...view(c), choice: null })));
const boxCarts = (s) =>
  !s.source.writeBoxart
    ? []
    : s.carts.filter(
        (c) =>
          !hasLabelFill(c) &&
          !c.boxOnCard &&
          !c.byHand &&
          !canRevert(c) &&
          !c.error &&
          !c.looking &&
          !c.boxFetching &&
          !!boxWrite(c),
      );
let savingCovers = null;

function updateFillButton() {
  if (!session) return;
  if (savingCovers) {
    $('shelf-fill').hidden = false;
    $('fill-status').textContent = t(`Saving ${savingCovers.done} of ${savingCovers.total}…`);
    $('fill-open').hidden = false;
    $('fill-open').textContent = t('Saving…');
    $('fill-open').disabled = true;
    $('upload-files').disabled = true;
    for (const c of session.carts) c.el.more.disabled = true;
    return;
  }
  const unlabelled = session.carts.filter((c) => !c.existingPng && !c.error && !c.looking).length;
  const counts = batchCounts(session);
  const fillable = counts.unlabelled - counts.neither;
  const covers = boxCarts(session).length;
  const bar = shelfFillBar({ unlabelled, fillable, covers });
  $('shelf-fill').hidden = bar.hidden;
  if (!bar.hidden) $('fill-status').textContent = t(bar.status);
  $('fill-open').hidden = !bar.action;
  if (bar.action) $('fill-open').textContent = t(bar.action);
  $('fill-open').disabled = writing || uploading > 0;
  $('upload-files').disabled = writing;
  for (const c of session.carts) c.el.more.disabled = writing || uploading > 0;
}

async function openFillOrSaveCovers(s) {
  if (!s || s !== session || writing || uploading > 0 || fatal) return;
  const counts = batchCounts(s);
  const fillable = counts.unlabelled - counts.neither;
  if (fillable > 0) {
    await askFill(s);
    return;
  }
  await saveCovers(s);
}

async function saveCovers(s) {
  if (!s || s !== session || writing || uploading > 0 || fatal) return;
  const carts = boxCarts(s);
  if (!carts.length) return;
  $('banner').hidden = true;
  $('banner').replaceChildren();
  writing = true;
  savingCovers = { done: 0, total: carts.length };
  updateFillButton();
  try {
    for (const [i, c] of carts.entries()) {
      if (fatal || s !== session) break;
      savingCovers.done = i + 1;
      updateFillButton();
      await persistCart(s, c);
      paint(c);
    }
  } catch (error) {
    if (!trapped(error)) banner(operationError(error));
  } finally {
    savingCovers = null;
    writing = false;
    updateFillButton();
  }
}

async function fillOrAsk(s) {
  const counts = batchCounts(s);
  if (counts.unlabelled === 0 || s !== session || fatal) return;
  const hasCandidate = counts.real > 0 || counts.logo > 0;
  const fill = s.fill ?? autoFill(counts) ?? 'real';
  answerFill(fill, hasCandidate);
}

async function askFill(s) {
  const counts = batchCounts(s);
  const fillable = counts.unlabelled - counts.neither;
  const covers = boxCarts(s).length;
  if (fillable === 0 || s !== session || fatal) return;
  const { title, question } = fillText({ ...counts, unlabelled: fillable, covers });
  $('fill-title').textContent = t(title);
  $('fill-question').textContent = t(question);
  $('fill-note').hidden = false;
  $('fill-note').textContent = t('Loading…');
  $('fill-save').disabled = true;
  $('fill-real').disabled = counts.real === 0;
  $('fill-logo').disabled = counts.logo === 0;
  const preferred = s.fill ?? 'real';
  selectFill(counts[preferred] > 0 ? preferred : counts.real > 0 ? 'real' : 'logo');
  if (!$('fill').open) {
    $('fill').showModal();
    $('fill').focus();
  }
  const sample =
    s.carts.find((c) => !c.existingPng && view(c).hasReal && view(c).hasLogo) ??
    s.carts.find((c) => !c.existingPng && (view(c).hasReal || view(c).hasLogo));
  if (sample) await fetchReal(s, sample);
  $('fill-save').disabled = false;
  if (s !== session || fatal || !$('fill').open) return;
  $('fill-note').hidden = true;
  for (const kind of ['real', 'logo']) {
    const canvas = $(`fill-${kind}`).querySelector('canvas');
    let face = null;
    try {
      face = sample ? faceFor(sample, kind) : null;
    } catch (e) {
      if (trapped(e)) return;
      throw e;
    }
    canvas.hidden = !face;
    if (face) {
      [canvas.width, canvas.height] = boxOf(sample.platform);
      drawFace(canvas, face, sample.platform);
    }
  }
}

function answerFill(fill, remember = true) {
  const s = session;
  if (!s) return;
  if (remember) s.fill = fill;
  for (const c of s.carts) {
    const v = view(c);
    if (!applyFill([v], fill)) continue;
    c.choice = v.choice;
    amend(c, (snap) => {
      if (snap.choice == null) snap.choice = fill;
    });
  }
  $('fill').close();
  for (const c of s.carts) {
    if (!c.userHue) c.deep = null;
    paint(c);
    if (needsReal(c)) track(fetchReal(s, c));
  }
  updateFillButton();
}

let selectedFill = 'real';
function selectFill(kind) {
  selectedFill = kind;
  for (const option of ['real', 'logo']) $(`fill-${option}`).setAttribute('aria-pressed', String(option === kind));
}

async function generateAndSave() {
  const s = session;
  if (!s || writing) return;
  const carts = batchCarts(s);
  const extraCovers = boxCarts(s);
  writing = true;
  updateFillButton();
  $('fill').setAttribute('aria-busy', 'true');
  for (const id of ['fill-save', 'fill-cancel', 'fill-real', 'fill-logo']) $(id).disabled = true;
  $('fill-note').hidden = false;
  $('fill-note').textContent = t('Saving…');
  let ok = true;
  let count = 0;
  try {
    s.fill = selectedFill;
    for (const c of carts) {
      if (fatal || s !== session) { ok = false; break; }
      const v = { ...view(c), choice: null };
      if (!applyFill([v], selectedFill)) continue;
      c.choice = v.choice;
      if (needsReal(c)) await fetchReal(s, c);
      if (stateOf(c) !== 'ready') { paint(c); continue; }
      count++;
      ok = (await persistCart(s, c)) && ok;
      paint(c);
    }
    for (const c of extraCovers) {
      if (fatal || s !== session) { ok = false; break; }
      if (!boxPending(c)) continue;
      count++;
      ok = (await persistCart(s, c)) && ok;
      paint(c);
    }
    const remaining = s === session ? batchCounts(s) : { unlabelled: 1, neither: 0 };
    if (count && (ok || remaining.unlabelled - remaining.neither === 0)) $('fill').close();
    else $('fill-note').textContent = t(count ? 'Couldn’t save all changes. Try again.' : 'No labels available to save.');
  } catch (error) {
    $('fill-note').textContent = operationError(error);
  } finally {
    writing = false;
    $('fill').setAttribute('aria-busy', 'false');
    const counts = s === session ? batchCounts(s) : { real: 1, logo: 1 };
    $('fill-save').disabled = false;
    $('fill-cancel').disabled = false;
    $('fill-real').disabled = counts.real === 0;
    $('fill-logo').disabled = counts.logo === 0;
    updateFillButton();
  }
}

async function persistCart(s, c) {
  if (fatal || s !== session) return false;
  return saveCart(c, {
    source: s.source,
    stopped: () => fatal || s !== session,
    labelReady: (c) => stateOf(c) === 'ready',
    labelRemove,
    makeLabel: (c) => withCartLabel(c, (label) => ({ png: label.png(), face: label.face(c.platform, c.code, c.head, c.shell, c.stem) })),
    writeShell: async (c) => {
      if (s.shellReadFailed) throw new Error('shell_read');
      const text = mergedShells(s, [c], [c.shell]);
      await s.source.writeShells(text);
      s.shellText = text;
    },
    failed: (part, error) => {
      if (!trapped(error)) banner(`${t({ label: 'Label', shell: 'Shell', box: 'Box art' }[part])}: ${operationError(error)}`);
    },
  });
}

const labelRemove = (c) => !c.error && !!c.existingPng && c.choice === 'none' && !!session?.source.removeLabel;
const boxPending = (c) => !!(session?.source.writeBoxart && boxWrite(c)) || !!(session?.source.removeBoxart && boxRemove(c));
const pendingSave = (c) => stateOf(c) === 'ready' || labelRemove(c) || c.shell !== c.shellOnCard || boxPending(c);
const canSave = (c) => !writing && uploading === 0 && !fatal && !c.reading && !c.error
  && !(wearing(c).kind === 'real' && !realBytesOf(c) && !c.real?.failed) && pendingSave(c);

async function saveOne(c) {
  if (!canSave(c)) return false;
  $('banner').hidden = true;
  $('banner').replaceChildren();
  writing = true;
  updateFillButton();
  try { return await persistCart(session, c); }
  finally { writing = false; paint(c); }
}

function operationError(error) {
  if (error.message === 'shell_read') return t('Shells not written: the card’s shells couldn’t be read.');
  if (error.status === 401) return t('Pairing code no longer valid');
  if (error.status === 429) return t(PAIR_NOTES.locked);
  if (error.code === 'offline') return t(PAIR_NOTES.offline);
  if (error.status === 503) return t(PAIR_NOTES.library);
  if (error.status === 404) return t('This game is no longer available. Reload the page.');
  if (error.status === 409 || error.message === 'conflict') return t('The library changed elsewhere. Reload before trying again.');
  return t('Operation failed. Check the connection and try again.');
}

async function removeGame(c) {
  if (writing || uploading > 0 || !session.source.remove) throw new Error('busy');
  const s = session;
  $('banner').hidden = true;
  $('banner').replaceChildren();
  writing = true;
  c.el.root.setAttribute('aria-busy', 'true');
  c.el.game.textContent = t('Deleting…');
  updateFillButton();
  try {
    await s.source.remove(c);
    s.carts = s.carts.filter((item) => item !== c);
    c.deleted = true;
    cardObserver?.unobserve(c.el.root);
    c.el.root.remove();
    if (!s.carts.some((other) => other.platform === c.platform && other.stem.normalize('NFC') === c.stem.normalize('NFC'))) {
      s.source.labels.delete(labelKey(c.platform, c.stem));
      s.source.boxarts?.delete(labelKey(c.platform, c.stem));
    }
    await loadShells(s);
    showPlatform(platformsOf(s).includes(s.platform) ? s.platform : platformsOf(s)[0] ?? PLATFORMS[0]);
    if (!s.carts.length) banner('No games yet. Upload games to start.');
  } finally {
    writing = false;
    c.el.root.removeAttribute('aria-busy');
    if (!c.deleted) paint(c);
    updateFillButton();
  }
}

const gameMenu = createGameMenu({
  supported: () => !!session?.source.remove,
  busy: () => writing || uploading > 0,
  remove: removeGame,
  failed: (error) => banner(operationError(error)),
});

const canLabel = (c) => !c.error && !c.looking && !c.lookupPending;

const SMALL = new Set(['in', 'of', 'the', 'and']);
const titled = (name) =>
  name
    .split(' ')
    .map((w, i) => (i > 0 && SMALL.has(w) ? w : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(' ');

let presetList = null;
const presets = () =>
  (presetList ??= shell_presets().map((entry) => {
    const [name, value] = entry.split('\t');
    return { name: t(titled(name)), value };
  }));

function shellOf(c) {
  const [, autoColour, autoFinish] = auto_shell(c.platform, c.code, c.head).split(' ');
  const { outline, colour, finish } = choiceOf(c);
  const auto = colour === autoColour && finish === autoFinish;
  const preset = presets().find(({ value }) => value.endsWith(` ${colour} ${finish}`));
  return { outline, colour, finish, auto, name: preset?.name ?? `#${colour}` };
}

function setMatchedGame(c, game, matchedCrc = null) {
  const previous = c.game;
  const previousCrc = c.matchedCrc;
  c.game = game;
  c.matchedCrc = matchedCrc;
  if (c.baseline) {
    // Automatic matching is metadata; it must not dirty or reset a shell draft.
    amend(c, (snap) => {
      if (snap.game === previous) snap.game = game;
      if (snap.matchedCrc === previousCrc) snap.matchedCrc = matchedCrc;
    });
  }
}

function lookUp(s, c) {
  if (c.lookupJob) return c.lookupJob;
  c.matchState = 'matching';
  c.artStates = {};
  c.looking = !c.existingPng;
  paint(c);
  c.lookupJob = (async () => {
    try {
      await artReady;
      if (c.crc === null) c.crc = await checksumOf(s, c);
      await ensureDat(c.platform);
      if (s !== session || fatal) return;
      if (!c.game && !c.rejected) {
        const resolved = resolveCartGame(c);
        setMatchedGame(c, resolved.game, resolved.crc);
      }
      await dress(s, c);
    } catch (e) {
      if (trapped(e)) return;
      c.matchState = 'error';
      logError(c.stem, e);
    } finally {
      c.lookupJob = null;
      c.looking = false;
      c.boxFetching = false;
      if (s === session && !fatal) paint(c);
    }
  })();
  return c.lookupJob;
}

function retryArtwork(c) {
  if (c.lookupJob || c.reading) return;
  c.real = null;
  c.boxTried = c.game;
  if (artFailure) artReady = fetchIndex(ART_BASE).then((index) => {
    artIndex = index; artFailure = null;
  }, (error) => { artFailure = error.message; });
  track(lookUp(session, c));
}

function setShell(c, next) {
  const [, autoColour, autoFinish] = auto_shell(c.platform, c.code, c.head).split(' ');
  if (!next) {
    const { outline } = choiceOf(c);
    c.shell = outline === 'auto' ? '' : `${outline} ${autoColour} ${autoFinish}`;
    return;
  }
  c.shell = next.outline === 'auto' && next.colour === autoColour && next.finish === autoFinish
    ? ''
    : `${next.outline} ${next.colour} ${next.finish}`;
}

function setBackground(c, rgb) {
  c.deep = rgb;
  c.userHue = !!rgb;
}

// A CRC can be right about the bytes and wrong about the game: a ROM renamed to another's name.
function noMatch(c) {
  c.rejected = true;
  c.matchedCrc = null;
  c.userHue = false;
  c.deep = null;
  if (c.existingPng && (c.choice === 'real' || c.choice === 'logo')) {
    c.choice = 'card';
    c.byHand = false;
  }
  if (c.boxChoice === 'fetched') {
    c.boxChoice = c.boxOnCard ? 'card' : null;
  }
}

// Painted at once, so the editor's buttons are right before the next click; only a drag's
// follow-on inputs wait for a frame.
function changed(c, soon = false) {
  try {
    if (c.existingPng) c.existing = existing_face(c.existingPng, c.platform, c.code, c.head, c.shell, c.stem);
  } catch (e) {
    if (trapped(e)) return;
    throw e;
  }
  if (soon) schedulePaint(c);
  else paint(c);
}

const editor = createEditor({
  artworkStatus,
  imageState,
  retryArtwork,
  canEdit: (c) => !fatal && !c.error && !c.reading,
  closed: closeNative,
  saved: () => { if (mode.local && mode.single) { editor.close(); closeNative(); } },
  name: (c) => clean_label(c.stem),
  queryName: (c) => searchQueryOf(c.stem) || clean_label(c.stem),
  box: boxOf,
  draw: drawFace,
  face: (c) => {
    if (fatal) return null;
    try {
      return faceOf(c, stateOf(c)) ?? c.existing ?? faceFor(c, 'none');
    } catch (e) {
      if (trapped(e)) return null;
      throw e;
    }
  },
  hex,
  fromHex,
  matched: (c) => !!c.game && !c.rejected,
  canLabel,
  canBackground: (c) => stateOf(c) === 'ready' && ['logo', 'customLogo'].includes(wearing(c).kind),
  wearing,
  available: (c, kind) => available(view(c), kind),
  realFailed: (c) => !!realOf(c)?.failed,
  retryReal,
  fill: () => session?.fill ?? null,
  tileFace: (c, kind) => {
    if (fatal) return null;
    try {
      return faceFor(c, kind);
    } catch (e) {
      if (trapped(e)) return null;
      throw e;
    }
  },
  choose,
  useCustomLogo,
  useCustomLabel,
  ensureTiles,
  boxSupported: () => !!session?.source.writeBoxart,
  boxWears,
  boxAvailable,
  boxBytes: (c, kind) => boxBytesOf(c, kind),
  boxBusy: (c) => !!(c.looking || c.lookupPending || c.boxFetching),
  chooseBox,
  useCustomBox,
  readBoxArt: readPicture,
  drawBox,
  canShell: (c) => !fatal && !c.reading && !c.error && shell_key_ok(c.stem),
  search: (c, query) => dats.get(c.platform)?.search(query, 30) ?? null,
  chooseGame,
  noMatch,
  readLogo,
  background: (c) => ({ rgb: c.deep ?? baseDeep(c), auto: !c.userHue }),
  setBackground,
  shellOf,
  setShell,
  presets,
  changed,
  canSave,
  save: saveOne,
  busy: () => writing || uploading > 0,
  error: operationError,
});

window.slotStudioBack = () => studioBack(document, editor, gameMenu, () => writing || uploading > 0);

// The handheld shows a six-character code; a QR code from it fills the code in already.
const PAIR_NOTES = {
  ask: 'Enter the 6-character code shown on the handheld.',
  pin: 'That code doesn’t match. Check the handheld and try again.',
  locked: 'Too many tries. Wait a minute, then try again.',
  offline: 'Can’t reach the handheld. Check that the connection is still on.',
  library: 'The handheld’s library isn’t available. Check its storage, then reload.',
  other: 'The handheld couldn’t read its library.',
};

function askPin(note) {
  loading(null);
  $('pair').hidden = false;
  $('pair-note').textContent = t(PAIR_NOTES[note]);
  $('pair-note').classList.toggle('bad', note !== 'ask');
  const input = $('pair-code');
  input.value = sessionStorage.getItem('slot-pin') ?? '';
  input.focus();
  input.select();
  return new Promise((resolve) => {
    $('pair').onsubmit = (e) => {
      e.preventDefault();
      const pin = input.value.trim().toUpperCase();
      if (pin.length !== 6) return;
      $('pair').onsubmit = null;
      resolve(pin);
    };
  });
}

async function pair() {
  let note = storedPin() ? null : 'ask';
  for (;;) {
    if (note) rememberPin(await askPin(note));
    $('pair-go').disabled = true;
    $('pair').hidden = true;
    loading('Reading the card…');
    try {
      const listing = await deviceLibrary();
      $('pair').hidden = true;
      return listing;
    } catch (e) {
      if (e.status === 401) forgetPin();
      note = e.status === 401 ? 'pin' : e.status === 429 ? 'locked' : e.code === 'offline' ? 'offline'
        : e.status === 503 ? 'library' : 'other';
    } finally {
      $('pair-go').disabled = false;
    }
  }
}

let reauthPending = null;
function reauthenticate() {
  if (reauthPending) return reauthPending;
  forgetPin();
  const dialog = $('reauth');
  const input = $('reauth-code');
  const note = $('reauth-note');
  const go = $('reauth-go');
  const cancel = $('reauth-cancel');
  const showNote = (key, bad = false) => {
    note.textContent = t(PAIR_NOTES[key]);
    note.classList.toggle('bad', bad);
  };
  showNote('ask', false);
  input.value = '';
  go.disabled = false;
  cancel.disabled = false;
  if (!dialog.open) dialog.showModal();
  input.focus();
  reauthPending = new Promise((resolve) => {
    const finish = (ok) => {
      $('reauth-form').onsubmit = null;
      cancel.onclick = null;
      dialog.oncancel = null;
      reauthPending = null;
      if (dialog.open) dialog.close();
      resolve(ok);
    };
    cancel.onclick = () => finish(false);
    dialog.oncancel = (e) => {
      e.preventDefault();
      if (!go.disabled) finish(false);
    };
    $('reauth-form').onsubmit = async (e) => {
      e.preventDefault();
      const pin = input.value.trim().toUpperCase();
      if (pin.length !== 6 || go.disabled) return;
      go.disabled = true;
      cancel.disabled = true;
      rememberPin(pin);
      try {
        const r = await fetch('api/library', { headers: authHeaders() });
        if (r.ok) {
          finish(true);
          return;
        }
        forgetPin();
        showNote(r.status === 401 ? 'pin' : r.status === 429 ? 'locked' : r.status === 503 ? 'library' : 'other', true);
      } catch {
        forgetPin();
        showNote('offline', true);
      } finally {
        go.disabled = false;
        cancel.disabled = false;
        if (dialog.open) {
          input.focus();
          input.select();
        }
      }
    };
  });
  return reauthPending;
}

const UPLOAD_PROBLEMS = {
  format: 'Not a .gba, .gb or .gbc file',
  size: 'Larger than 64 MiB',
  incomplete: 'Incomplete file',
  name: 'Invalid file name',
  pin: 'Pairing code no longer valid',
};
let uploads = Promise.resolve();
let uploading = 0;

function uploadRow(file) {
  const row = Object.assign(document.createElement('li'), { className: 'upload' });
  const name = Object.assign(document.createElement('span'), { className: 'name', textContent: file.name, title: file.name });
  const state = Object.assign(document.createElement('span'), { className: 'state', textContent: t('Waiting') });
  const bar = Object.assign(document.createElement('progress'), { max: 1, value: 0 });
  bar.hidden = true;
  row.append(name, state, bar);
  return {
    row,
    show(text, kind = '') {
      state.textContent = t(text);
      row.className = `upload ${kind}`;
    },
    bar,
  };
}

function clearUploads() {
  if (uploading > 0) return;
  $('uploads').replaceChildren();
  $('uploads-clear').hidden = true;
  $('uploads-wrap').hidden = true;
}

// Sends files one after another; a file that fails leaves the rest to go on.
function sendGames(files) {
  if (!session || writing || !files.length) return;
  if (uploading === 0) $('uploads').replaceChildren();
  $('uploads-wrap').hidden = false;
  $('uploads-clear').hidden = true;
  const rows = files.map((file) => ({ file, view: uploadRow(file) }));
  $('uploads').append(...rows.map((r) => r.view.row));
  uploading += rows.length;
  updateFillButton();
  editor.refresh();
  uploads = uploads.then(async () => {
    let added = false;
    for (const { file, view } of rows) {
      const problem = checkRom(file);
      let result;
      if (problem) result = { error: problem };
      else {
        view.show('Uploading');
        view.bar.hidden = false;
        result = await uploadFile(file, (done) => (view.bar.value = done));
        view.bar.hidden = true;
      }
      if (result.result === 'added') { view.show('Added', 'added'); added = true; }
      else if (result.result === 'skipped') view.show('Skipped, already on the handheld');
      else view.show(UPLOAD_PROBLEMS[result.error] ?? 'Upload failed', 'failed');
      uploading--;
    }
    $('uploads-clear').hidden = uploading > 0;
    updateFillButton();
    editor.refresh();
    if (!added) return;
    try {
      await extend(session, await deviceLibrary());
    } catch (e) {
      if (!trapped(e)) banner(operationError(e));
    }
  });
}

function wireUploads() {
  if (mode.local) {
    document.addEventListener('dragover', (e) => e.preventDefault());
    document.addEventListener('drop', (e) => e.preventDefault());
    return;
  }
  $('upload-files').addEventListener('change', (e) => {
    const files = [...e.target.files];
    e.target.value = '';
    sendGames(files);
  });
  const carriesFiles = (e) => [...(e.dataTransfer?.types ?? [])].includes('Files');
  let depth = 0;
  document.addEventListener('dragenter', (e) => {
    if (!session || !carriesFiles(e)) return;
    depth++;
    document.body.classList.add('dropping');
  });
  document.addEventListener('dragleave', (e) => {
    if (!carriesFiles(e)) return;
    depth = Math.max(0, depth - 1);
    if (depth === 0) document.body.classList.remove('dropping');
  });
  document.addEventListener('dragover', (e) => {
    if (session && carriesFiles(e)) e.preventDefault();
  });
  document.addEventListener('drop', (e) => {
    depth = 0;
    document.body.classList.remove('dropping');
    // A drop on a cart's face is a logo for that cart.
    if (e.defaultPrevented || !session || !carriesFiles(e)) return;
    e.preventDefault();
    sendGames([...e.dataTransfer.files]);
  });
}

async function start() {
  translatePage();
  loading('Loading…');
  await init();
  for (const platform of PLATFORMS) faceBox.set(platform, Array.from(cart_size(platform)));
  setArtHeaders(authHeaders);
  wireUploads();
  $('uploads-clear').addEventListener('click', clearUploads);
  $('fill-open').addEventListener('click', () => track(openFillOrSaveCovers(session)));
  for (const kind of ['real', 'logo']) $(`fill-${kind}`).addEventListener('click', () => selectFill(kind));
  $('fill-save').addEventListener('click', () => track(generateAndSave()));
  $('fill-cancel').addEventListener('click', () => { if (!writing) $('fill').close(); });
  $('fill').addEventListener('cancel', (e) => {
    if (writing) e.preventDefault();
  });
  let fillPressedBehind = false;
  $('fill').addEventListener('pointerdown', (e) => {
    fillPressedBehind = e.target === $('fill');
  });
  $('fill').addEventListener('click', (e) => {
    if (fillPressedBehind && e.target === $('fill') && !writing) $('fill').close();
    fillPressedBehind = false;
  });
  window.addEventListener('beforeunload', (e) => {
    if (writing || session?.carts.some(canRevert)) { e.preventDefault(); e.returnValue = ''; }
  });
  const listing = mode.local ? (storedPin(), await deviceLibrary()) : await pair();
  setPinHandler(mode.local ? null : reauthenticate);
  // A set of our own, if the address named one. Not having it is not a failure: every cart falls
  // back to libretro, which is all the page had before there was a set to point at.
  artFailure = null;
  if (ART_BASE) {
    artReady = fetchIndex(ART_BASE).then(
      (index) => {
        artIndex = index;
        console.info(`art set: ${Object.keys(artIndex).length} checksums from ${ART_BASE}`);
      },
      (e) => {
        artFailure = `The art set at ${ART_BASE} didn’t load (${e.message}), so carts get slot’s own labels.`;
      },
    );
  }
  await open(fromDevice(singleListing(listing, mode)));
  $('upload-label').hidden = mode.local || mode.single;
  $('upload-hint').hidden = mode.local || mode.single;

  // slot's masthead: a row side by side, a list behind a button on a phone.
  const menu = (open) => {
    $('mast-links').classList.toggle('is-open', open);
    $('menu-btn').setAttribute('aria-expanded', String(open));
  };
  $('menu-btn').addEventListener('click', (e) => {
    e.stopPropagation();
    menu(!$('mast-links').classList.contains('is-open'));
  });
  document.addEventListener('click', (e) => {
    if (!$('menu-btn').contains(e.target)) menu(false);
  });


  // For tools/verify.py, which has no native picker to click. Never set on the published site.
  if (['localhost', '127.0.0.1'].includes(location.hostname)) {
    window.__studio = {
      openFiles: (files) => open(fromFiles(files)),
      openDirectory: async (root) => open(await fromDirectory(root)),
      idle: () =>
        busy === 0 && session !== null && session.carts.every((c) => stateOf(c) !== 'looking'),
      states: () =>
        session
          ? session.carts.map((c) => ({
              platform: c.platform,
              stem: c.stem,
              state: stateOf(c),
              game: c.game,
              deep: hex(c.deep ?? baseDeep(c)),
              band: !!bandOf(c),
              logo: (wearing(c).kind === 'customLogo' ? c.customLogoBytes : c.logoBytes)?.length ?? 0,
              wears: wearing(c).kind,
              why: wearing(c).why,
            }))
          : [],
      shells: () => (session ? session.carts.map((c) => ({ stem: c.stem, shell: c.shell })) : []),
      shellPresets: () => shell_presets(),
      editing: () => editor.current()?.stem ?? null,
      artBase: () => ART_BASE,
      fill: () => session?.fill ?? null,
    };
  }
  document.body.dataset.ready = 'true';
}

start().catch((error) => {
  loading(null);
  logError('start', error);
  banner('Couldn’t open Slot Manager. Try again.', () => location.reload());
});
