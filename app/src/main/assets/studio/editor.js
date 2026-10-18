// One game's appearance draft, reset baseline and save controls.
import { canRevert, commit, revert, visit, visited } from './history.js';
import { decide } from './decision.js';
import { arrange } from './palette.js';
import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);
const OPENERS = { 'ed-games': 'ed-game', 'ed-bg-pal': 'ed-bg', 'ed-shell-pal': 'ed-shell' };

export function createEditor(api) {
  const panel = $('editor');
  let cart = null;
  let built = false;
  // A colour picker drag captures the baseline once, then coalesces rendering.
  let dragging = false;
  let working = false;
  let closing = false;

  // A message raised while the editor is open lives inside it, where the page's own banner is out of sight.
  function say(text, kind = 'info') {
    $('ed-note').textContent = text;
    $('ed-note').dataset.kind = kind;
    $('ed-note').hidden = !text;
  }

  function change(fn, c = cart) {
    dragging = false;
    say('');
    if (!working && c && commit(c, () => fn(c))) api.changed(c);
  }

  function drag(fn) {
    const c = cart;
    if (!c || working) return;
    if (dragging) {
      fn(c);
      api.changed(c, true);
    } else if ((dragging = commit(c, () => fn(c)))) {
      api.changed(c);
    }
  }

  function step(fn) {
    dragging = false;
    say('');
    const c = cart;
    if (c && fn(c)) api.changed(c);
  }

  // Keep focus inside the modal when a control is hidden or disabled.
  function keepFocus(fallback = panel, lost = false) {
    const at = document.activeElement;
    if (cart && (lost || (panel.contains(at) && (at.disabled || at.closest('[hidden]'))))) {
      (fallback.disabled ? panel : fallback).focus({ preventScroll: true });
    }
  }

  function closePops() {
    dragging = false;
    panel.classList.remove('searching-games');
    $('ed-tip').hidden = true;
    for (const [pop, opener] of Object.entries(OPENERS)) {
      const had = $(pop).contains(document.activeElement);
      $(pop).hidden = true;
      $(opener).setAttribute('aria-expanded', 'false');
      if (had) keepFocus($(opener), true);
    }
  }

  function toggle(pop) {
    const opening = $(pop).hidden;
    closePops();
    $(pop).hidden = !opening;
    $(OPENERS[pop]).setAttribute('aria-expanded', String(opening));
    return opening;
  }

  const press = (group, value) => {
    for (const b of $(group).querySelectorAll('button')) b.setAttribute('aria-pressed', String(b.dataset.value === value));
  };
  const enable = (group, on) => {
    for (const b of $(group).querySelectorAll('button')) b.disabled = !on;
  };

  // Background and Shell share one palette: the real plastics, by colour.
  function build() {
    if (built) return;
    built = true;
    const { neutrals, colours } = arrange(api.presets());
    const swatch = ({ name, value }) => {
      const b = Object.assign(document.createElement('button'), { type: 'button' });
      b.setAttribute('aria-label', name);
      b.dataset.value = value;
      b.dataset.finish = value.split(' ')[2];
      b.style.backgroundColor = `#${value.split(' ')[1]}`;
      return b;
    };
    for (const id of ['ed-bg-presets', 'ed-shell-presets']) {
      const gap = Object.assign(document.createElement('span'), { className: 'pal-break' });
      $(id).replaceChildren(...neutrals.map(swatch), gap, ...colours.map(swatch));
    }
  }

  const presetNamed = (hex) => api.presets().find(({ value }) => `#${value.split(' ')[1]}` === hex)?.name ?? hex;

  function showGames() {
    const c = cart;
    const item = (text, pick, className = '') => {
      const b = Object.assign(document.createElement('button'), { type: 'button', textContent: text, className });
      b.addEventListener('click', () => {
        closePops();
        pick();
      });
      const li = document.createElement('li');
      li.append(b);
      return li;
    };
    const query = $('ed-query').value;
    $('ed-query-clear').hidden = !query;
    const results = api.search(c, query);
    const emptyText = results === null
      ? t('Game database is not available')
      : results.length === 0 && query.trim()
        ? t('No matching games. Try searching in English.')
        : '';
    const emptyItem = emptyText
      ? [Object.assign(document.createElement('li'), { className: 'hits-empty', textContent: emptyText })]
      : [];
    $('ed-hits').replaceChildren(
      ...(api.matched(c) ? [item(t('No match'), () => change(api.noMatch), 'none')] : []),
      ...(results ?? []).map((name) =>
        item(name, () => change((c) => api.chooseGame(c, name))),
      ),
      ...emptyItem,
    );
  }

  // The optional label and box-art tiles live behind a switch in the section title.
  function setSwitch(id, on) {
    const button = $(id);
    const row = button.closest('.ed-label');
    button.setAttribute('aria-checked', String(on));
    row.classList.toggle('off', !on);
  }
  const labelOn = (c) => api.wearing(c).kind !== 'none';
  const boxOn = (c) => api.boxWears(c) !== 'none';

  function renderArtworkStatus(c) {
    const state = api.artworkStatus(c);
    const action = $('ed-art-action');
    const focused = document.activeElement === action;
    $('ed-art-status').hidden = !state;
    $('ed-art-status').querySelector('.spinner').hidden = !state?.busy;
    $('ed-art-status-text').textContent = state ? t(state.text) : '';
    action.hidden = !state?.action;
    action.textContent = state?.action ? t(state.action) : '';
    action.disabled = working;
    if (focused && action.hidden) keepFocus($('ed-game'), true);
  }

  // Reserve a preview-sized placeholder so individual downloads do not collapse the tiles.
  function renderImageState(tile, state, hasPreview) {
    let placeholder = tile.querySelector('.art-placeholder');
    if (!placeholder) {
      placeholder = document.createElement('span');
      placeholder.className = 'art-placeholder';
      const spinner = document.createElement('span');
      spinner.className = 'spinner';
      spinner.setAttribute('aria-hidden', 'true');
      placeholder.append(spinner);
      tile.prepend(placeholder);
    }
    placeholder.hidden = hasPreview;
    placeholder.querySelector('.spinner').hidden = state !== 'loading';
    tile.dataset.artState = state;
    tile.setAttribute('aria-busy', String(state === 'loading'));
    tile.querySelector('small').textContent = t(state === 'loading' ? 'Loading…'
      : state === 'error' ? 'Couldn’t load. Click to try again' : state === 'empty' ? 'No image available' : '');
  }

  function renderTiles(c) {
    const wearing = api.wearing(c);
    const fill = api.fill();
    for (const tile of $('ed-tiles').querySelectorAll('.tile')) {
      const kind = tile.dataset.kind;
      const ok = api.available(c, kind);
      const automatic = kind === 'real' || kind === 'logo';
      const state = automatic ? api.imageState(c, kind) : null;
      const retry = state === 'error';
      tile.hidden = kind === 'card' && !ok;
      tile.disabled = kind === 'customLogo' || kind === 'customLabel'
        ? !api.canEdit(c)
        : !api.canEdit(c) || (!ok && !retry) || (automatic && state === 'loading');
      tile.setAttribute('aria-pressed', String(wearing.kind === kind));
      const badge = tile.querySelector('.badge.default');
      if (badge) badge.hidden = fill !== kind;
      const small = tile.querySelector('small');
      const hasFile = kind === 'customLogo' ? !!c.customLogoBytes : kind === 'customLabel' ? !!c.customLabelBytes : true;
      tile.classList.toggle('has-file', hasFile);
      if (small && (kind === 'customLogo' || kind === 'customLabel')) small.textContent = hasFile ? t('Click again to use another file') : '';
      const canvas = tile.querySelector('canvas');
      const face = ok && hasFile ? api.tileFace(c, kind) : null;
      if (automatic) renderImageState(tile, state, !!face);
      canvas.hidden = !face;
      if (face) {
        [canvas.width, canvas.height] = api.box(c.platform);
        api.draw(canvas, face, c.platform);
      }
    }
    setSwitch('ed-label-on', labelOn(c));
    $('ed-label-on').disabled = !api.canEdit(c);
  }

  // Box art tiles: what the handheld holds, what libretro has for the game, and a picture of one's own.
  function renderBox(c) {
    const section = $('ed-box-section');
    section.hidden = !api.boxSupported();
    if (section.hidden) return;
    const wearing = api.boxWears(c);
    for (const tile of $('ed-box-tiles').querySelectorAll('.tile')) {
      const kind = tile.dataset.kind;
      const ok = api.boxAvailable(c, kind);
      const state = kind === 'fetched' ? api.imageState(c, kind) : null;
      tile.hidden = kind === 'card' && !ok;
      tile.disabled = !api.canEdit(c) || (!ok && kind !== 'custom' && state !== 'error') || state === 'loading';
      tile.setAttribute('aria-pressed', String(wearing === kind));
      const hasFile = kind !== 'custom' || !!c.customBoxBytes;
      tile.classList.toggle('has-file', hasFile);
      const badge = tile.querySelector('.badge.default');
      if (badge) badge.hidden = !ok;
      const small = tile.querySelector('small');
      if (small) {
        small.textContent =
          kind === 'fetched' ? ''
          : kind === 'custom' && hasFile ? t('Click again to use another file')
          : '';
      }
      const bytes = ok && hasFile ? api.boxBytes(c, kind) : null;
      if (kind === 'fetched') renderImageState(tile, state, !!bytes);
      const canvas = tile.querySelector('canvas');
      if (canvas) {
        canvas.hidden = !bytes;
        if (bytes) api.drawBox(canvas, bytes);
      }
    }
    setSwitch('ed-box-on', boxOn(c));
    $('ed-box-on').disabled = !!c.error;
  }

  function refresh() {
    const c = cart;
    if (!c) return;
    if (!visited(c)) visit(c);
    $('ed-name').textContent = api.name(c);
    renderArtworkStatus(c);
    const face = api.face(c);
    $('ed-face').hidden = !face;
    if (face) api.draw($('ed-face'), face, c.platform);

    const label = api.canLabel(c);
    $('ed-game').disabled = !label;
    $('ed-game').querySelector('span').textContent = api.matched(c) ? c.game : t('Choose a game');
    renderTiles(c);
    renderBox(c);

    const bg = api.background(c);
    $('ed-bg').closest('.prop').hidden = !api.canBackground(c);
    $('ed-bg').querySelector('i').style.background = api.hex(bg.rgb);
    $('ed-bg').querySelector('span').textContent = bg.auto ? t('Automatic') : presetNamed(api.hex(bg.rgb));
    for (const b of $('ed-bg-presets').querySelectorAll('button')) {
      b.setAttribute('aria-pressed', String(!bg.auto && `#${b.dataset.value.split(' ')[1]}` === api.hex(bg.rgb)));
    }
    $('ed-bg-auto').setAttribute('aria-pressed', String(bg.auto));
    $('ed-bg-custom').value = api.hex(bg.rgb);

    const shell = api.shellOf(c);
    const shells = api.canShell(c);
    $('ed-shell').disabled = !shells;
    $('ed-shell').querySelector('i').style.background = `#${shell.colour}`;
    $('ed-shell').querySelector('span').textContent = shell.auto ? t('Automatic') : shell.name;
    for (const b of $('ed-shell-presets').querySelectorAll('button')) {
      const [, colour, finish] = b.dataset.value.split(' ');
      b.setAttribute('aria-pressed', String(!shell.auto && colour === shell.colour && finish === shell.finish));
    }
    $('ed-shell-auto').setAttribute('aria-pressed', String(shell.auto));
    $('ed-shell-custom').value = `#${shell.colour}`;
    press('ed-finish', shell.finish);
    enable('ed-finish', shells);
    $('ed-outline-row').hidden = c.platform === 'GBA';
    press('ed-outline', shell.outline);
    enable('ed-outline', shells);

    if (Object.entries(OPENERS).some(([pop, opener]) => !$(pop).hidden && $(opener).disabled)) closePops();
    $('ed-revert').disabled = working || !api.canEdit(c) || !canRevert(c);
    $('ed-save').disabled = working || !api.canSave(c);
    $('ed-close').disabled = working;
    panel.querySelector('.ed-body').inert = working;
    panel.setAttribute('aria-busy', String(working));
    keepFocus();
  }

  function open(c) {
    build();
    if (c === cart) return;
    cart?.el.root.classList.remove('current');
    cart = c;
    dragging = false;
    say('');
    api.ensureTiles(c);
    if (!visited(c)) visit(c);
    closePops();
    c.el.root.classList.add('current');
    [$('ed-face').width, $('ed-face').height] = api.box(c.platform);
    if (!panel.open) panel.showModal();
    document.body.classList.add('editing');
    refresh();
    panel.focus({ preventScroll: true });
  }

  // Programmatic closure also releases the selected card.
  function teardown() {
    if (!cart) return;
    cart.el.root.classList.remove('current');
    cart = null;
    closePops();
    say('');
    document.body.classList.remove('editing');
  }

  function close() {
    if (panel.open) panel.close();
    teardown();
  }

  async function save() {
    if (!cart || working || !api.canSave(cart)) return false;
    working = true;
    say(t('Saving…'));
    closePops();
    refresh();
    try {
      const result = await api.save(cart);
      if (result) say(t('Saved'), 'ok');
      else if ($('ed-note').textContent === t('Saving…')) say(t('Couldn’t save all changes. Try again.'), 'error');
      return result;
    } catch (error) {
      say(api.error(error), 'error');
      return false;
    } finally {
      working = false;
      refresh();
    }
  }

  async function settle() {
    if (!cart || !canRevert(cart)) return true;
    if (cart.reading || api.busy()) { say(t('Please wait for loading to finish.')); return false; }
    if (!api.canSave(cart)) { visit(cart); return true; }
    const answer = await decide({ title: 'Unsaved changes', text: 'Save changes to this game?', discard: true });
    if (answer === 'save') return save();
    if (answer === 'discard') { step(revert); return true; }
    return false;
  }

  async function requestClose() {
    if (working || closing) return;
    closing = true;
    try { if (await settle()) { close(); api.closed?.(); } }
    finally { closing = false; }
  }

  // A swatch's name, at once and kept inside the window; the browser's own tooltip is slow to come.
  function tip(b) {
    const t = $('ed-tip');
    if (!b) {
      t.hidden = true;
      return;
    }
    t.textContent = b.getAttribute('aria-label');
    t.hidden = false;
    const r = b.getBoundingClientRect();
    const left = Math.min(Math.max(8, r.left + r.width / 2 - t.offsetWidth / 2), innerWidth - t.offsetWidth - 8);
    t.style.left = `${left}px`;
    t.style.top = `${Math.max(8, r.top - t.offsetHeight - 6)}px`;
  }
  const swatch = (e) => e.target.closest?.('.pal-grid button') ?? null;
  for (const id of ['ed-bg-presets', 'ed-shell-presets']) {
    $(id).addEventListener('pointerover', (e) => tip(swatch(e)));
    $(id).addEventListener('pointerout', () => tip(null));
    $(id).addEventListener('focusin', (e) => tip(swatch(e)));
    $(id).addEventListener('focusout', () => tip(null));
  }

  $('ed-close').addEventListener('click', requestClose);
  $('ed-save').addEventListener('click', async () => {
    if (await save()) api.saved?.();
  });
  panel.addEventListener('close', teardown);
  // Escape first closes an open list, then the editor. The key is handled here, since a search box
  // would otherwise spend it clearing itself; the dialog's own cancel covers the ways in that carry
  // no key, such as a back gesture.
  let escaped = false;
  function back() {
    if (Object.keys(OPENERS).some((pop) => !$(pop).hidden)) closePops();
    else requestClose();
  }
  panel.addEventListener('cancel', (e) => {
    // The file picker's own cancel event bubbles up to here; only the dialog's counts.
    if (e.target !== panel) return;
    e.preventDefault();
    if (!escaped) back();
  });
  // A click on the dimmed page behind closes it, unless the press began inside the editor.
  // Clicking inside the editor outside an open popover closes that popover.
  let pressedBehind = false;
  panel.addEventListener('pointerdown', (e) => {
    pressedBehind = e.target === panel;
    for (const [pop, opener] of Object.entries(OPENERS)) {
      if (!$(pop).hidden && !$(pop).contains(e.target) && !$(opener).contains(e.target)) {
        closePops();
        break;
      }
    }
  });
  panel.addEventListener('click', (e) => {
    if (pressedBehind && e.target === panel) requestClose();
    pressedBehind = false;
  });

  $('ed-game').addEventListener('click', () => {
    if (!toggle('ed-games')) return;
    $('ed-query').value = (api.queryName ?? api.name)(cart);
    showGames();
    if (!document.body.classList.contains('local-studio') && !window.matchMedia?.('(pointer: coarse)').matches) {
      $('ed-query').select();
    } else {
      ($('ed-hits').querySelector('button') ?? $('ed-query')).focus({ preventScroll: true });
    }
  });
  $('ed-art-action').addEventListener('click', () => {
    if (!cart || working) return;
    if (api.artworkStatus(cart)?.action === 'Choose a game') $('ed-game').click();
    else api.retryArtwork(cart);
  });
  $('ed-query').addEventListener('input', showGames);
  $('ed-query').addEventListener('focus', () => panel.classList.add('searching-games'));
  $('ed-query').addEventListener('blur', () => {
    setTimeout(() => {
      if (document.activeElement !== $('ed-query')) panel.classList.remove('searching-games');
    }, 80);
  });
  $('ed-query').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      $('ed-query').blur();
    }
  });
  $('ed-query-clear').addEventListener('pointerdown', (e) => e.preventDefault());
  $('ed-query-clear').addEventListener('click', () => {
    $('ed-query').value = '';
    showGames();
    $('ed-query').focus({ preventScroll: true });
  });

  let picking = null;
  function pickImage(kind, accept) {
    picking = kind;
    if (window.AndroidStudio?.pickImage) {
      window.AndroidStudio.pickImage();
      return;
    }
    $('ed-file').accept = accept;
    $('ed-file').click();
  }
  async function applyPickedFile(file) {
    const c = cart;
    const kind = picking;
    const bytes = kind === 'customBox' ? await api.readBoxArt(file) : await api.readLogo(file);
    if (!bytes || !kind || c !== cart || working) return;
    change((c) => {
      if (kind === 'customBox') api.useCustomBox(c, bytes);
      else if (kind === 'customLogo') api.useCustomLogo(c, bytes);
      else api.useCustomLabel(c, bytes);
    }, c);
  }
  window.slotStudioPickResult = (base64, name = 'image.png', mime = 'image/png') => {
    if (!base64) return;
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    applyPickedFile(new File([bytes], name, { type: mime }));
  };
  $('ed-box-tiles').addEventListener('click', (e) => {
    const tile = e.target.closest('.tile');
    if (!tile || tile.disabled) return;
    const kind = tile.dataset.kind;
    const c = cart;
    if (kind === 'custom' && (!c.customBoxBytes || c.boxChoice === 'custom')) {
      pickImage('customBox', 'image/*');
      return;
    }
    if (kind === 'fetched' && api.imageState(c, kind) === 'error') { api.retryArtwork(c); return; }
    change((c) => (kind === 'custom' ? api.useCustomBox(c, c.customBoxBytes) : api.chooseBox(c, kind)));
  });
  $('ed-tiles').addEventListener('click', (e) => {
    const tile = e.target.closest('.tile');
    if (!tile || tile.disabled) return;
    const kind = tile.dataset.kind;
    const c = cart;
    const file = kind === 'customLogo' ? c.customLogoBytes : kind === 'customLabel' ? c.customLabelBytes : null;
    if ((kind === 'real' || kind === 'logo') && api.imageState(c, kind) === 'error') { api.retryArtwork(c); return; }
    if ((kind === 'customLogo' || kind === 'customLabel') && (!file || c.choice === kind)) {
      pickImage(kind, 'image/*');
      return;
    }
    if (kind === 'real') api.retryReal(c);
    if (kind === 'customLogo') change((c) => api.useCustomLogo(c, file));
    else if (kind === 'customLabel') change((c) => api.useCustomLabel(c, file));
    else change((c) => api.choose(c, kind));
  });
  $('ed-label-on').addEventListener('click', () => {
    if (!cart || working) return;
    change((c) => {
      if (labelOn(c)) api.choose(c, 'none');
      else api.choose(c, c.choice && c.choice !== 'none' ? c.choice : api.fill() ?? 'logo');
    });
  });
  $('ed-box-on').addEventListener('click', () => {
    if (!cart || working) return;
    if (!boxOn(cart)) {
      const target = cart.boxChoice && cart.boxChoice !== 'none' ? cart.boxChoice : 'fetched';
      if ((target === 'custom' && !cart.customBoxBytes) || (target === 'fetched' && !api.boxAvailable(cart, 'fetched') && !cart.boxOnCard)) {
        if (cart.customBoxBytes) change((c) => api.useCustomBox(c, cart.customBoxBytes));
        else pickImage('customBox', 'image/*');
        return;
      }
    }
    change((c) => {
      if (boxOn(c)) api.chooseBox(c, 'none');
      else api.chooseBox(c, c.boxChoice && c.boxChoice !== 'none' ? c.boxChoice : 'fetched');
    });
  });
  $('ed-file').addEventListener('change', async () => {
    const file = $('ed-file').files[0];
    $('ed-file').value = '';
    await applyPickedFile(file);
  });

  $('ed-bg').addEventListener('click', () => toggle('ed-bg-pal'));
  $('ed-bg-presets').addEventListener('click', (e) => {
    const value = e.target.closest('button')?.dataset.value;
    if (!value) return;
    closePops();
    change((c) => api.setBackground(c, api.fromHex(`#${value.split(' ')[1]}`)));
  });
  $('ed-bg-auto').addEventListener('click', () => {
    closePops();
    change((c) => api.setBackground(c, null));
  });
  $('ed-bg-custom').addEventListener('input', () =>
    drag((c) => api.setBackground(c, api.fromHex($('ed-bg-custom').value))),
  );

  $('ed-shell').addEventListener('click', () => toggle('ed-shell-pal'));
  $('ed-shell-presets').addEventListener('click', (e) => {
    const value = e.target.closest('button')?.dataset.value;
    if (!value) return;
    const [, colour, finish] = value.split(' ');
    closePops();
    change((c) => api.setShell(c, { ...api.shellOf(c), colour, finish }));
  });
  $('ed-shell-auto').addEventListener('click', () => {
    closePops();
    change((c) => api.setShell(c, null));
  });
  $('ed-shell-custom').addEventListener('input', () =>
    drag((c) => api.setShell(c, { ...api.shellOf(c), colour: $('ed-shell-custom').value.slice(1) })),
  );
  for (const id of ['ed-bg-custom', 'ed-shell-custom']) {
    $(id).addEventListener('change', () => {
      dragging = false;
      closePops();
    });
  }
  $('ed-finish').addEventListener('click', (e) => {
    const finish = e.target.closest('button')?.dataset.value;
    if (finish) change((c) => api.setShell(c, { ...api.shellOf(c), finish }));
  });
  $('ed-outline').addEventListener('click', (e) => {
    const outline = e.target.closest('button')?.dataset.value;
    if (outline) change((c) => api.setShell(c, { ...api.shellOf(c), outline }));
  });

  $('ed-revert').addEventListener('click', () => step(revert));

  panel.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      escaped = true;
      setTimeout(() => (escaped = false));
      back();
      e.preventDefault();
      return;
    }
  });

  return { open, close, refresh, say, requestClose, back, current: () => cart };
}
