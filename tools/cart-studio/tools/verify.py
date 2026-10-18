#!/usr/bin/env python3
"""Open slot's test card in the studio in headless Chrome, the way a browser without a directory
picker opens it, then screenshot the page at 1280 and 400 wide and download the labels zip.

Everything lands in out/. The card is slot's own sdcard/ Games folder with its Labels/ left
behind, so every cart gets a generated label that can be set beside its hand-made one.

A second pass opens a card that already has labels for three of its carts, one of them spelled
decomposed, and fails unless those three stay out of the zip. In the zip flow nothing else stops
labels.zip from overwriting a label someone made by hand.
"""

import base64
import functools
import http.server
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
import zipfile
import zlib
from pathlib import Path

import websocket

STUDIO = Path(__file__).resolve().parent.parent
SLOT_GAMES = STUDIO.parent / 'slot' / 'sdcard' / 'Games'
OUT = STUDIO / 'out'
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
# Overridable so a run can avoid whichever port the user's own browser session is on.
HTTP_PORT = int(os.environ.get('STUDIO_HTTP_PORT', 8765))
CDP_PORT = int(os.environ.get('STUDIO_CDP_PORT', 9223))
# The second pass's labels: two of slot's hand-made ones, and Emerald's under an extra cart whose
# ROM is named composed and whose label decomposed, so only a normalised comparison pairs them.
KEPT = ['Advance Wars', 'Metroid Fusion']
PROBE = unicodedata.normalize('NFC', 'Pokémon Probe')
PROBE_OF = 'Pokemon - Emerald Version (USA, Europe)'
# What the labelled card already holds: a comment and another cart's choice, both of which a
# write must leave as they are.
SHELLS_ON_CARD = '# chosen by hand\nCatrap (USA) = rounded 112233 solid\n'
# And the layer over it, beside the labels, which is the one the studio writes.
LABEL_SHELLS_ON_CARD = 'Tetris Rosy Retrospection = auto 445566 clear\n'

OPEN_FILES = """
(async (card, paths) => {
  const files = [];
  for (const path of paths) {
    const r = await fetch('/' + card + '/' + path.split('/').map(encodeURIComponent).join('/'));
    if (!r.ok) throw new Error(path + ': HTTP ' + r.status);
    const file = new File([await r.blob()], path.split('/').pop());
    Object.defineProperty(file, 'webkitRelativePath', { value: card + '/' + path });
    files.push(file);
  }
  await window.__studio.openFiles(files);
  return files.length;
})(%s, %s)
"""

CART_CANVAS = """
(stem => document.querySelectorAll('#grid > .cart')[
  window.__studio.states().findIndex(s => s.stem === stem)].querySelector('canvas').toDataURL())(%s)
"""
CANVAS_HIDDEN = """
(stem => document.querySelectorAll('#grid > .cart')[
  window.__studio.states().findIndex(s => s.stem === stem)].querySelector('canvas').hidden)(%s)
"""
# One cart's card element, by stem, as a JS expression.
CARD = "document.querySelectorAll('#grid > .cart')[window.__studio.states().findIndex(s => s.stem === %s)]"
EDIT = '(' + CARD + ").click()"
# The stems on the shelf showing, in order.
SHELF = ("[...document.querySelectorAll('#grid > .cart')].filter(c => !c.hidden)"
         ".map(c => c.querySelector('.stem').title)")
# Search the Game row for `game` and pick it.
PICK_HIT = """
(game => {
  const query = document.getElementById('ed-query');
  query.value = game;
  query.dispatchEvent(new Event('input'));
  const hit = [...document.querySelectorAll('#ed-hits button')].find(b => b.textContent === game);
  if (!hit) throw new Error('no hit for ' + game);
  hit.click();
  return true;
})(%s)
"""
# Hand the Custom picker a file that is not a PNG.
PICK_JUNK = """
(() => {
  document.querySelector('#ed-tiles [data-kind="customLogo"]').click();
  const files = new DataTransfer();
  files.items.add(new File(['not a png'], 'notes.png', { type: 'image/png' }));
  const input = document.getElementById('ed-file');
  input.files = files.files;
  input.dispatchEvent(new Event('change'));
  return true;
})()
"""
UNDO_KEY = ("document.getElementById('editor').dispatchEvent("
            "new KeyboardEvent('keydown', { key: 'z', metaKey: true, bubbles: true }))")
UNDO_FOCUSED = ("(document.activeElement || document.body).dispatchEvent("
                "new KeyboardEvent('keydown', { key: 'z', metaKey: true, bubbles: true }))")
# One input from the Background picker, with no change event after it: a picker still open.
DRAG_ONLY = """
(v => { const i = document.getElementById('ed-bg-custom'); i.value = v; i.dispatchEvent(new Event('input')); })(%s)
"""
# Hand the Custom picker the PNG at `url`.
PICK_LOGO = """
(async url => {
  document.querySelector('#ed-tiles [data-kind="customLogo"]').click();
  const blob = await (await fetch(url)).blob();
  const files = new DataTransfer();
  files.items.add(new File([blob], 'logo.png', { type: 'image/png' }));
  const input = document.getElementById('ed-file');
  input.files = files.files;
  input.dispatchEvent(new Event('change'));
  return true;
})(%s)
"""
# Drop a small PNG drawn on the spot onto one cart's face, by stem.
DROP_ON_FACE = """
(async stem => {
  const card = document.querySelectorAll('#grid > .cart')[window.__studio.states().findIndex(s => s.stem === stem)];
  const canvas = Object.assign(document.createElement('canvas'), { width: 64, height: 32 });
  const g = canvas.getContext('2d');
  g.fillStyle = '#fff';
  g.fillRect(8, 8, 48, 16);
  const blob = await new Promise(r => canvas.toBlob(r, 'image/png'));
  const data = new DataTransfer();
  data.items.add(new File([blob], 'logo.png', { type: 'image/png' }));
  card.querySelector('.face').dispatchEvent(new DragEvent('drop', { dataTransfer: data, bubbles: true, cancelable: true }));
  return true;
})(%s)
"""


class Quiet(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        '.wasm': 'application/wasm',
        '.js': 'text/javascript',
    }

    def log_message(self, *args):
        pass


class Cdp:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=300)
        self.last = 0
        # An exception thrown inside a paint leaves the page half drawn and says nothing. The
        # events arrive between the replies to our own calls, so they are collected there.
        self.problems = []

    def send(self, method, **params):
        self.last += 1
        self.ws.send(json.dumps({'id': self.last, 'method': method, 'params': params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get('method') == 'Runtime.exceptionThrown':
                d = msg['params']['exceptionDetails']
                text = d.get('exception', {}).get('description') or d.get('text', '')
                self.problems.append(text.splitlines()[0] if text else 'unknown exception')
            elif msg.get('method') == 'Runtime.consoleAPICalled' and msg['params']['type'] == 'error':
                args = ' '.join(str(a.get('value', a.get('description', ''))) for a in msg['params']['args'])
                self.problems.append(f'console.error: {args}'[:200])
            if msg.get('id') == self.last:
                if 'error' in msg:
                    raise RuntimeError(f'{method}: {msg["error"]}')
                return msg.get('result', {})

    def eval(self, expression):
        r = self.send('Runtime.evaluate', expression=expression, awaitPromise=True, returnByValue=True)
        if 'exceptionDetails' in r:
            raise RuntimeError(json.dumps(r['exceptionDetails'], indent=1))
        return r['result'].get('value')

    def width(self, width):
        self.send('Emulation.setDeviceMetricsOverride', width=width, height=900, deviceScaleFactor=1, mobile=False)
        time.sleep(0.5)

    def shot(self, width, path):
        self.width(width)
        height = self.eval('document.documentElement.scrollHeight')
        r = self.send('Page.captureScreenshot', format='png', captureBeyondViewport=True,
                      clip={'x': 0, 'y': 0, 'width': width, 'height': height, 'scale': 1})
        path.write_bytes(base64.b64decode(r['data']))
        print(f'{path.name}: {width}x{height}')


def wait(check, timeout, what):
    end = time.time() + timeout
    while True:
        try:
            value = check()
            if value:
                return value
        except OSError:
            pass
        if time.time() > end:
            sys.exit(f'timed out waiting for {what}')
        time.sleep(0.25)


def nfc(name):
    return unicodedata.normalize('NFC', name)


def state_of(page, stem):
    return next(s for s in page.eval('window.__studio.states()') if nfc(s['stem']) == nfc(stem))


def shell_of(page, stem):
    return next(s['shell'] for s in page.eval('window.__studio.shells()') if nfc(s['stem']) == nfc(stem))


def pick_shell(page, i):
    page.eval("document.getElementById('ed-shell').click()")
    page.eval("document.querySelectorAll('#ed-shell-presets button')[%d].click()" % i)


def background(page, colour):
    """A drag through the Background picker ending on `colour`: two inputs, then the change."""
    page.eval("""(v => {
      document.getElementById('ed-bg').click();
      const i = document.getElementById('ed-bg-custom');
      for (const at of ['#111111', v]) { i.value = at; i.dispatchEvent(new Event('input')); }
      i.dispatchEvent(new Event('change'));
    })(%s)""" % json.dumps(colour))


def open_card(page, card, paths):
    """Hands the page a card's files and waits for every cart to settle. Returns their states."""
    print('opened', page.eval(OPEN_FILES % (json.dumps(card), json.dumps(paths))), 'files')
    wait(lambda: page.eval('window.__studio.idle()'), 600, 'every cart to be looked up')
    if page.eval("document.getElementById('fill').open"):
        page.eval("document.getElementById('fill-logo').click()")
        wait(lambda: page.eval('window.__studio.idle()'), 600, 'every cart to be filled')
    states = page.eval('window.__studio.states()')
    for s in states:
        print(f"  {s['state']:<10} {s['deep']}  {s['stem']}  ->  {s['game']}")
    return states


def stage_labelled(card, games):
    """slot's carts, the probe cart, and the labels in KEPT and for the probe. Returns the paths
    the page is handed, spelled as they were written."""
    # A symlink per ROM rather than one for the folder, so the probe lands here and not in slot.
    for g in games:
        (card / 'Games' / g).parent.mkdir(parents=True, exist_ok=True)
        (card / 'Games' / g).symlink_to(SLOT_GAMES / g)
    probe = f'GBA/{PROBE}.gba'
    (card / 'Games' / probe).symlink_to(SLOT_GAMES / f'GBA/{PROBE_OF}.gba')
    # The labels the first pass just wrote, not the live card's: a fixture that reads slot's sdcard
    # breaks whenever that card changes, and it did.
    written = OUT / 'card' / 'Labels'
    labels = {f'GBA/{stem}.png': written / f'GBA/{stem}.png' for stem in KEPT}
    labels[unicodedata.normalize('NFD', f'GBA/{PROBE}.png')] = written / f'GBA/{PROBE_OF}.png'
    for name, source in labels.items():
        (card / 'Labels' / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(source, card / 'Labels' / name)
    (card / 'Config').mkdir(parents=True, exist_ok=True)
    (card / 'Config' / 'cart_shell.ini').write_text(SHELLS_ON_CARD)
    (card / 'Labels' / 'cart_shell.ini').write_text(LABEL_SHELLS_ON_CARD)
    return ([f'Games/{g}' for g in games] + [f'Games/{probe}'] + [f'Labels/{name}' for name in labels]
            + ['Config/cart_shell.ini', 'Labels/cart_shell.ini'])


def main():
    if not (STUDIO / 'web' / 'pkg' / 'slot_cart_studio.js').exists():
        sys.exit('web/pkg is missing: run `task build` first')
    OUT.mkdir(exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='studio-verify-'))
    (stage / 'studio').symlink_to(STUDIO / 'web')
    (stage / 'card').mkdir()
    (stage / 'card' / 'Games').symlink_to(SLOT_GAMES)
    # Relative to Games/, platform folder and all: slot files a cart under the folder its
    # platform names, and the folder is what decides the platform.
    games = sorted(
        f'{d.name}/{p.name}'
        for d in SLOT_GAMES.iterdir()
        if d.is_dir() and not d.name.startswith('.')
        for p in d.iterdir()
        if p.is_file() and not p.name.startswith('.')
    )

    server = http.server.ThreadingHTTPServer(('127.0.0.1', HTTP_PORT), functools.partial(Quiet, directory=str(stage)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    profile = Path(tempfile.mkdtemp(prefix='studio-chrome-'))
    chrome = subprocess.Popen(
        [CHROME, '--headless=new', f'--remote-debugging-port={CDP_PORT}', '--remote-allow-origins=*',
         f'--user-data-dir={profile}', '--no-first-run', '--no-default-browser-check', 'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        wait(lambda: json.load(urllib.request.urlopen(f'http://127.0.0.1:{CDP_PORT}/json/version')), 30, 'Chrome')
        downloads = OUT / 'downloads'
        shutil.rmtree(downloads, ignore_errors=True)
        downloads.mkdir()

        new = urllib.request.Request(f'http://127.0.0.1:{CDP_PORT}/json/new?about:blank', method='PUT')
        page = Cdp(json.load(urllib.request.urlopen(new))['webSocketDebuggerUrl'])
        page.send('Runtime.enable')
        page.width(1280)
        # STUDIO_ART points the page at a harvested set, the way the address does by hand; unset, the
        # local one `task art` serves. Every logo comes from the set, so a run needs one.
        art = os.environ.get('STUDIO_ART', 'http://127.0.0.1:8766/')
        try:
            urllib.request.urlopen(urllib.request.Request(art.rstrip('/') + '/index.json',
                                                          headers={'User-Agent': 'slot-cart-studio-verify'}))
        except OSError as e:
            sys.exit(f'no art set at {art} ({e}): run `task art`, or point STUDIO_ART at one')
        # With no address the page reaches for the published set; the runs below always name one,
        # an empty one meaning none, so they never depend on the network or its CORS rules.
        bare = f'http://127.0.0.1:{HTTP_PORT}/studio/'
        page.send('Page.navigate', url=bare)
        wait(lambda: page.eval("document.body && document.body.dataset.ready === 'true'"), 60, 'the bare studio to start')
        if page.eval('window.__studio.artBase()') != 'https://art.slot-cfw.fyi/':
            sys.exit(f'with no ?art= the studio uses {page.eval("window.__studio.artBase()")!r}, not the published set')
        print('asserted: with no ?art= the studio uses https://art.slot-cfw.fyi/')
        widths = page.eval("[document.querySelector('.credits'), document.querySelector('main.wrap')]"
                           ".map((e, i) => i ? e.clientWidth - parseFloat(getComputedStyle(e).paddingLeft) * 2"
                           " : e.getBoundingClientRect().width)")
        if abs(widths[0] - widths[1]) > 1:
            sys.exit(f'the footer is {widths[0]:.0f} px wide inside a {widths[1]:.0f} px page')
        print(f'asserted: the footer is as wide as the page ({widths[0]:.0f} px)')
        centred = page.eval("['.intro', '.credits'].map(q => getComputedStyle(document.querySelector(q)).textAlign)"
                            ".concat(getComputedStyle(document.querySelector('.actions')).justifyContent)")
        if centred != ['center', 'center', 'center']:
            sys.exit(f'the hero and footer are not centred: {centred}')
        print('asserted: the hero and footer are centred')
        page_url = bare + '?art=' + urllib.parse.quote(art, safe='')
        print('art set:', art or '(none)')
        page.send('Page.navigate', url=page_url)
        wait(lambda: page.eval("document.body && document.body.dataset.ready === 'true'"), 60, 'the studio to start')

        page.eval('performance.setResourceTimingBufferSize(10000)')
        first = open_card(page, 'card', ['Games/' + g for g in games])

        # Logos come from the curated set alone: nothing is asked of libretro's logo folders.
        asked = page.eval("performance.getEntriesByType('resource').map(e => e.name).filter(n => n.includes('/Named_Logos/'))")
        if asked:
            sys.exit(f'the page asked libretro for {len(asked)} logos, e.g. {asked[0]}')
        print('asserted: no logo is asked of libretro-thumbnails')

        # The write button sits on the platform tabs' row, at its right end.
        where_btn = page.eval("""(() => {
          const b = document.getElementById('write').getBoundingClientRect();
          const t = document.querySelector('#tabs button').getBoundingClientRect();
          const g = document.getElementById('grid').getBoundingClientRect();
          return [b.top < t.bottom && b.bottom > t.top, Math.round(g.right - b.right)];
        })()""")
        if where_btn != [True, 0]:
            sys.exit(f'the write button is not at the right end of the tabs\' row: {where_btn}')
        print('asserted: the write button sits at the right end of the platform tabs')
        sizes = page.eval("[document.getElementById('write'), document.querySelector('#tabs button')]"
                          ".map(e => (r => [Math.round(r.height), Math.round(r.bottom)])(e.getBoundingClientRect()))")
        if sizes[0] != sizes[1]:
            sys.exit(f'the write button (height, bottom) {sizes[0]} does not match the tabs {sizes[1]}')
        print(f'asserted: the write button is the tabs\' height and stands on the same edge ({sizes[0][0]} px)')
        beside = page.eval("[...document.getElementById('write-bar').children].map(e => e.id)")
        if beside != ['write']:
            sys.exit(f'the write button has company: {beside}')
        print('asserted: the write button stands alone, with no count or directions')

        # slot's own masthead, exactly: its mark, its six sections, and the studio as the current page.
        mast = page.eval("[...document.querySelectorAll('.mast a')]"
                         ".map(a => [a.textContent.trim(), a.getAttribute('href'), a.getAttribute('aria-current')])")
        site = 'https://slot-cfw.fyi/'
        want_mast = [['slot.', site, None]] + [[name, f'{site}#{anchor}', None] for name, anchor in (
            ('Installing', 'install'), ('Guide', 'guide'), ('Buttons', 'buttons'), ('Questions', 'questions'),
            ('Credits', 'credits'), ('AI', 'disclosure'))] + [['Studio', './', 'page']]
        if mast != want_mast:
            sys.exit(f'the masthead is {mast}, not slot\'s {want_mast}')
        print('asserted: the masthead is slot\'s, link for link')

        # Every platform tab is the same key, whichever platform it names.
        shapes = page.eval("[...document.querySelectorAll('#tabs button')].map(b => (s => [s.borderRadius, s.clipPath, s.paddingLeft])(getComputedStyle(b)))")
        if len({json.dumps(x) for x in shapes}) > 1:
            sys.exit(f'the platform tabs are not all the same shape: {shapes}')
        print(f'asserted: all {len(shapes)} platform tabs are the same shape')
        order = page.eval("[...document.querySelectorAll('#tabs button')].map(b => b.firstChild.textContent)")
        if order != ['Game Boy Advance', 'Game Boy Color', 'Game Boy']:
            sys.exit(f'the platform tabs run {order}, not Game Boy Advance, Game Boy Color, Game Boy')
        print('asserted: the tabs run Game Boy Advance, Game Boy Color, Game Boy')

        # The page's own button is the one slot's hero uses: solid ink, 8px corners, nothing moulded.
        btn = page.eval("(s => [s.backgroundColor, s.backgroundImage, s.borderRadius, s.fontWeight, s.fontSize])"
                        "(getComputedStyle(document.getElementById('write')))")
        if btn != ['rgb(244, 245, 249)', 'none', '8px', '600', '16px']:
            sys.exit(f'the write button is not slot\'s hero button: {btn}')
        print('asserted: the write button is slot\'s hero button')

        # The fine print a real GBA label carries, instead of region pills; Game Boy carts carry none.
        prints = {nfc(s['stem']): (s['platform'], p) for s, p in zip(
            page.eval('window.__studio.states()'),
            page.eval("[...document.querySelectorAll('#grid > .cart')].map(c => c.querySelector('.print').textContent)"))}
        wrong = {stem: p for stem, (platform, p) in prints.items() if (platform == 'GBA') != p.startswith('AGB')}
        if wrong or prints.get('Advance Wars', (None, None))[1] != 'AGB-AWRE-USA':
            sys.exit(f'the small print is wrong: {wrong or prints.get("Advance Wars")}')
        print(f'asserted: every card carries its small print, e.g. Advance Wars {prints["Advance Wars"][1]}')

        # No pills and no spaced-out capitals anywhere on the page, the editor included.
        page.eval(EDIT % json.dumps(first[0]['stem']))
        page.eval("document.getElementById('ed-shell').click()")
        generic = page.eval("""[...document.querySelectorAll('body *')].filter(e => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden')
          .flatMap(e => {
            const s = getComputedStyle(e), r = e.getBoundingClientRect(), rad = parseFloat(s.borderTopLeftRadius) || 0;
            const name = e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className === 'string' ? '.' + e.className.split(' ')[0] : '');
            const out = [];
            if (s.textTransform === 'uppercase') out.push('uppercase ' + name);
            if (parseFloat(s.letterSpacing) > 1) out.push('tracked ' + name);
            if (r.height > 4 && rad >= r.height / 2 - 1 && r.width > r.height * 1.5) out.push('pill ' + name);
            return out;
          })""")
        page.eval("document.getElementById('ed-shell').click()")
        page.eval("document.getElementById('ed-close').click()")
        if generic:
            sys.exit(f'these still read as generic: {sorted(set(generic))}')
        print('asserted: no pills and no spaced capitals on the page or in the editor')

        # Credits name every source and its terms, whichever of them this visit used.
        links = page.eval("[...document.querySelectorAll('.credits a')].filter(a => a.getClientRects().length).map(a => a.href)")
        need = ['https://github.com/libretro/libretro-database', 'https://creativecommons.org/licenses/by-sa/4.0/',
                'https://www.screenscraper.fr/', 'https://creativecommons.org/licenses/by-nc-sa/4.0/']
        missing = [u for u in need if not any(l.startswith(u) for l in links)]
        if missing:
            sys.exit(f'the credits are missing {missing}')
        if 'not affiliated with nintendo' not in page.eval("document.querySelector('.credits').innerText").lower():
            sys.exit('the credits carry no Nintendo non-affiliation line')
        print('asserted: the credits name every source and its terms')

        # Settled again before the shots: open_card's wait ends when every cart has resolved once,
        # and a shot taken while anything is still being dressed photographs a spinner.
        wait(lambda: page.eval('window.__studio.idle()'), 120, 'the carts to settle before the shot')
        # With an art set that has looks: a sure look's ground is the label colour, and its band
        # can be switched off and on; an unsure look leaves the label colour alone.
        if art:
            base = art.rstrip('/') + '/'
            # Cloudflare turns away Python's own user agent, so this one says what it is.
            ask = urllib.request.Request(base + 'index.json', headers={'User-Agent': 'slot-cart-studio-verify'})
            index = json.load(urllib.request.urlopen(ask))
            looks, wheels = {}, {}
            for g in games:
                crc = f'{zlib.crc32((SLOT_GAMES / g).read_bytes()) & 0xffffffff:08X}'
                if 'look' in index.get(crc, {}):
                    looks[nfc(Path(g).stem)] = index[crc]['look']
                if 'wheel' in index.get(crc, {}):
                    wheels[nfc(Path(g).stem)] = base + index[crc]['wheel']
            states = {nfc(s['stem']): s for s in page.eval('window.__studio.states()')}
            ready = {stem: lk for stem, lk in looks.items() if states.get(stem, {}).get('state') == 'ready'}
            for stem, lk in ready.items():
                deep = states[stem]['deep'].lstrip('#')
                if lk['sure'] and deep != lk['ground']:
                    sys.exit(f'{stem} has a sure look {lk["ground"]} but its label colour is {deep}')
                if not lk['sure'] and deep == lk['ground']:
                    sys.exit(f'{stem} has an unsure look but wears its ground {deep}')
            print(f'asserted: {len(ready)} carts with looks use their ground only when sure')
            banded = next((s for s, lk in ready.items() if lk['sure'] and lk['band']), None)
            if banded is None:
                sys.exit('no cart on the card has a sure look with a band; the Classic NES Series carts should')
            on = page.eval(CART_CANVAS % json.dumps(banded))
            if not state_of(page, banded)['band']:
                sys.exit(f'{banded} has a sure band but does not wear it')
            print(f'asserted: {banded} wears its band')

            # A logo picked by hand is the person's own label: no measured colour or band on it,
            # and Undo gives the look back.
            other = next(url for stem, url in wheels.items() if stem != banded)
            page.eval(EDIT % json.dumps(banded))
            page.eval(PICK_LOGO % json.dumps(other))
            wait(lambda: page.eval(CART_CANVAS % json.dumps(banded)) != on, 20, 'the picked logo to show')
            picked = state_of(page, banded)
            if picked['band'] or picked['deep'].lstrip('#') == looks[banded]['ground']:
                sys.exit(f'{banded} still wears its look after a logo was picked for it: {picked}')
            custom = page.eval("document.querySelector('#ed-tiles [data-kind=\"customLogo\"]').getAttribute('aria-pressed')")
            if custom != 'true':
                sys.exit('the Custom logo tile is not chosen after a logo was picked')
            page.eval("document.getElementById('ed-undo').click()")
            wait(lambda: page.eval(CART_CANVAS % json.dumps(banded)) == on, 20, 'Undo to give the look back')
            if not state_of(page, banded)['band']:
                sys.exit(f'Undo gave {banded} its logo back but not its band')
            page.eval("document.getElementById('ed-close').click()")
            print(f'asserted: a logo picked for {banded} takes no colour or band from its look, and Undo gives them back')

            # A game chosen by hand wears that game's art, not the art filed under the cart's own checksum.
            by_stem = {nfc(s['stem']): s for s in page.eval('window.__studio.states()')}
            donor = next((s for stem, s in by_stem.items() if stem in wheels and stem != banded and s['game']
                          and s['platform'] == by_stem[banded]['platform'] and s['logo']
                          and s['logo'] != by_stem[banded]['logo']), None)
            if donor is None:
                sys.exit(f'no other cart with art on {banded}\'s platform to choose as its game')
            page.eval(EDIT % json.dumps(banded))
            page.eval("document.getElementById('ed-game').click()")
            page.eval(PICK_HIT % json.dumps(donor['game']))
            wait(lambda: page.eval('window.__studio.idle()'), 60, 'the chosen game to be dressed')
            now = state_of(page, banded)
            if (now['game'], now['logo']) != (donor['game'], donor['logo']):
                sys.exit(f'choosing {donor["game"]} for {banded} gave it {now["game"]!r} with a '
                         f'{now["logo"]}-byte logo, not the {donor["logo"]}-byte one')
            page.eval("document.getElementById('ed-undo').click()")
            if state_of(page, banded)['logo'] != by_stem[banded]['logo']:
                sys.exit('Undo did not give back the logo the chosen game replaced')
            page.eval("document.getElementById('ed-close').click()")
            print(f'asserted: choosing {donor["game"]} for {banded} brings that game\'s logo, and Undo takes it back')
        # The editor: a click on another card moves it there.
        shelf = page.eval(SHELF)
        if len(shelf) < 3:
            sys.exit(f'the first shelf has {len(shelf)} carts; the editor checks need three')
        page.eval(EDIT % json.dumps(shelf[0]))
        if page.eval("!!document.getElementById('ed-prev') || !!document.getElementById('ed-next')"):
            sys.exit('the editor still has ◀ ▶')
        page.eval('(' + CARD % json.dumps(shelf[2]) + ').click()')
        after_click = page.eval('window.__studio.editing()')
        if after_click != shelf[2]:
            sys.exit(f'the editor went to {after_click!r}, not {shelf[2]!r}')
        outlined = page.eval("[...document.querySelectorAll('#grid > .cart.current')]"
                             ".map(c => c.querySelector('.stem').title)")
        if outlined != [shelf[2]]:
            sys.exit(f'the outlined cards are {outlined}, not just {shelf[2]!r}')
        print('asserted: the editor has no ◀ ▶, and a click on a card moves it there')

        # Every row is a step in the cart's history. Choosing the game it already has, or a file
        # that isn't a PNG, is none.
        presets = [p.split('\t')[1] for p in page.eval('window.__studio.shellPresets()')]
        chosen = next((s['stem'] for s in first if s['state'] == 'ready' and s['platform'] == 'GBA'
                       and s['game'] and s['stem'] in shelf), None)
        if chosen is None:
            sys.exit('no ready, matched GBA cart on the first shelf to edit')
        page.eval(EDIT % json.dumps(chosen))
        if not page.eval("document.getElementById('ed-outline-row').hidden"):
            sys.exit('a GBA cart shows the Outline row')
        page.eval("document.getElementById('ed-game').click()")
        page.eval(PICK_HIT % json.dumps(state_of(page, chosen)['game']))
        if not page.eval("document.getElementById('ed-undo').disabled"):
            sys.exit('choosing the game the cart already has made an undo step')
        page.eval(PICK_JUNK)
        wait(lambda: 'notes.png' in page.eval("document.getElementById('banner').textContent"), 10,
             'the banner for a logo that is not a PNG')
        logo = page.eval("document.querySelector('#ed-tiles [data-kind=\"logo\"]').getAttribute('aria-pressed')")
        if not page.eval("document.getElementById('ed-undo').disabled") or logo != 'true':
            sys.exit('a file that is not a PNG changed the cart')
        page.eval("document.getElementById('banner').hidden = true")
        print('asserted: choosing the same game, or a file that is not a PNG, changes nothing')

        def look():
            return shell_of(page, chosen), state_of(page, chosen)['deep']

        was = look()
        pick_shell(page, 5)
        background(page, '#123456')
        both = (presets[5], '#123456')
        seen = [look()]
        page.eval("document.getElementById('ed-undo').click()")
        seen.append(look())
        page.eval(UNDO_KEY)
        seen.append(look())
        page.eval("document.getElementById('ed-redo').click()")
        page.eval("document.getElementById('ed-redo').click()")
        seen.append(look())
        page.eval("document.getElementById('ed-revert').click()")
        seen.append(look())
        page.eval("document.getElementById('ed-undo').click()")
        seen.append(look())
        page.eval("document.getElementById('ed-revert').click()")
        seen.append(look())
        want = [both, (presets[5], was[1]), was, both, was, both, was]
        if seen != want:
            sys.exit(f'Shell then Background stepped through {seen}, not {want}')
        # Keys go wherever focus is, and a pick hides the swatch that had it: ⌘Z must still reach the panel.
        page.eval("document.getElementById('ed-shell').click()")
        page.eval("(b => { b.focus(); b.click(); })(document.querySelectorAll('#ed-shell-presets button')[5])")
        page.eval(UNDO_FOCUSED)
        if look() != was:
            sys.exit(f'⌘Z after picking a swatch by keyboard focus did not undo it: {look()}')
        # A drag after an Undo is a step of its own, even when no change event came in between.
        page.eval("document.getElementById('ed-bg').click()")
        page.eval(DRAG_ONLY % json.dumps('#222222'))
        page.eval("document.getElementById('ed-undo').click()")
        page.eval(DRAG_ONLY % json.dumps('#333333'))
        if not page.eval("document.getElementById('ed-redo').disabled"):
            sys.exit('a drag after an Undo left the old Redo in place')
        page.eval("document.getElementById('ed-undo').click()")
        if look() != was:
            sys.exit(f'Undo did not take back a drag made after an Undo: {look()}')
        print('asserted: ⌘Z still works after a pick hides the focused swatch, and a drag after Undo is its own step')
        # Background and Shell are the same control: the same swatches, then Automatic and Custom….
        palettes = page.eval(
            "['ed-bg-pal', 'ed-shell-pal'].map(id => [...document.getElementById(id).querySelectorAll('button, input')]"
            ".map(b => b.tagName === 'INPUT' ? 'custom' : b.id ? b.textContent : b.style.background))"
        )
        if palettes[0] != palettes[1]:
            sys.exit(f'the Background palette {palettes[0]} is not the Shell one {palettes[1]}')
        swatch = page.eval("(b => ({ name: b.getAttribute('aria-label'), colour: b.dataset.value.split(' ')[1] }))"
                           "(document.querySelectorAll('#ed-bg-pal .pal-grid button')[5])")
        page.eval("document.getElementById('ed-bg').click()")
        page.eval("document.querySelectorAll('#ed-bg-pal .pal-grid button')[5].click()")
        named = page.eval("document.querySelector('#ed-bg span').textContent")
        if (look()[1], named) != ('#' + swatch['colour'], swatch['name']):
            sys.exit(f'picking {swatch} for Background gave {look()[1]!r}, labelled {named!r}')
        page.eval("document.getElementById('ed-undo').click()")
        if look() != was:
            sys.exit('Undo did not take back a Background swatch')
        print('asserted: Background has the Shell palette, and a swatch sets and names the colour')
        # Every colour name is capitalised, and hovering a swatch shows its name at once.
        names = page.eval("[...document.querySelectorAll('#ed-shell-presets button')].map(b => b.getAttribute('aria-label'))")
        small = {'in', 'of', 'the', 'and'}
        lower = [n for n in names if any(w[:1].islower() and (i == 0 or w not in small) for i, w in enumerate(n.split()))]
        if lower:
            sys.exit(f'these colour names are not capitalised: {lower}')
        page.eval("document.getElementById('ed-shell').click()")
        at = page.eval("(r => [r.left + r.width / 2, r.top + r.height / 2])"
                       "(document.querySelectorAll('#ed-shell-presets button')[0].getBoundingClientRect())")
        page.send('Input.dispatchMouseEvent', type='mouseMoved', x=at[0], y=at[1])
        tip = page.eval("(t => t.hidden ? null : [t.textContent, (r => r.left >= 0 && r.right <= innerWidth)"
                        "(t.getBoundingClientRect())])(document.getElementById('ed-tip'))")
        if tip != [names[0], True]:
            sys.exit(f'hovering the first swatch showed {tip!r}, not {names[0]!r} inside the window')
        page.send('Input.dispatchMouseEvent', type='mouseMoved', x=5, y=5)
        if not page.eval("document.getElementById('ed-tip').hidden"):
            sys.exit('the colour name stayed up after the pointer left')
        page.eval("document.getElementById('ed-shell').click()")
        print(f'asserted: colour names are capitalised, and hovering a swatch shows its name ({names[0]})')
        page.eval("document.getElementById('ed-close').click()")
        if page.eval('window.__studio.editing()') is not None or page.eval("!!document.querySelector('.cart.current')"):
            sys.exit('✕ left the editor open or a card outlined')
        print(f'asserted: on {chosen}, Undo (button and ⌘Z), Redo and Revert step Shell then Background, '
              'a drag is one step, and Revert is undoable')
        page.shot(1280, OUT / 'studio-1280.png')
        page.shot(400, OUT / 'studio-400.png')
        overflow = page.eval('document.documentElement.scrollWidth')
        print('scroll width at 400:', overflow)
        if overflow > 400:
            sys.exit('the page scrolls sideways at 400 px')

        # The switcher, clicked rather than counted: the Game Boy shelves are where the unmatched
        # carts live, so this is also the only shot that shows what an unmatched cart offers.
        tabs = page.eval("[...document.querySelectorAll('#tabs button')].map(b => b.textContent)")
        print('tabs:', tabs)
        if len(tabs) > 1:
            page.eval(EDIT % json.dumps(shelf[0]))
            page.eval("document.querySelectorAll('#tabs button')[%d].click()" % (len(tabs) - 1))
            if page.eval('window.__studio.editing()') is not None or page.eval("!!document.querySelector('.cart.current')"):
                sys.exit('switching shelves left the editor open')
            print('asserted: switching shelves closes the editor')
            wait(lambda: page.eval('window.__studio.idle()'), 120, 'the shelf to settle')
            print('at the shot: idle', page.eval('window.__studio.idle()'),
                  '| button', repr(page.eval("document.getElementById('write').textContent")),
                  '| banner', repr(page.eval("document.getElementById('banner').hidden")))
            # Per card: what paint wrote to the dom against what the page thinks the cart is. They
            # disagree only if paint stopped part way through, which has one early return in it.
            rows = page.eval(
                "[...document.querySelectorAll('.cart')].map(c => ["
                "  c.querySelector('.stem').textContent, c.dataset.state,"
                "  c.querySelector('.game').textContent,"
                "  c.querySelector('canvas').hidden ? 'no canvas' : 'canvas'].join(' | '))"
            )
            says = {s['stem']: s['state'] for s in page.eval('window.__studio.states()')}
            for r in rows:
                print('   dom:', r)
            print('   states:', json.dumps(says))
            for problem in dict.fromkeys(page.problems):
                print('   PAGE ERROR:', problem)
            shown = page.eval(
                "[...document.querySelectorAll('#grid > *')].filter(c => !c.hidden)"
                ".map(c => c.querySelector('.stem').textContent)"
            )
            print(f'{tabs[-1]} shows: {shown}')
            page.shot(1280, OUT / 'studio-tab-1280.png')
            if not shown:
                sys.exit(f'switching to {tabs[-1]} left no carts on screen')

        needs_logo = next((s['stem'] for s in first if s['state'] == 'needs-logo'), None)
        if needs_logo is None:
            sys.exit('no needs-logo cart in the fixture to check the generated label on')
        if page.eval(CANVAS_HIDDEN % json.dumps(needs_logo)):
            sys.exit(f'{needs_logo} (needs-logo) does not show the generated label')
        print(f'asserted: {needs_logo} (needs-logo) shows the generated label')
        page.eval(DROP_ON_FACE % json.dumps(needs_logo))
        wait(lambda: state_of(page, needs_logo)['state'] == 'ready', 10, 'the dropped logo to make it ready')
        page.eval(EDIT % json.dumps(needs_logo))
        gba = state_of(page, needs_logo)['platform'] == 'GBA'
        if page.eval("document.getElementById('ed-outline-row').hidden") != gba:
            sys.exit(f'the Outline row is wrong for {needs_logo}: it shows only on Game Boy carts')
        page.eval("document.getElementById('ed-undo').click()")
        if state_of(page, needs_logo)['state'] != 'needs-logo':
            sys.exit('Undo did not take the dropped logo back off')
        page.eval("document.getElementById('ed-close').click()")
        print(f'asserted: a PNG dropped on {needs_logo} is its logo, and Undo takes it off')

        # A card with no System/cart_shell.ini of its own must still get one in the zip.
        page.eval(EDIT % json.dumps('Drill Dozer'))
        pick_shell(page, 5)
        page.eval("document.getElementById('ed-close').click()")

        page.width(1280)
        # Headless Chrome ignores the browser-level Browser.setDownloadBehavior for this target;
        # only the page-level form of the command takes effect.
        page.send('Page.setDownloadBehavior', behavior='allow', downloadPath=str(downloads))
        page.eval("document.getElementById('write').click()")
        # click() returns at the loop's first yield, and this round trip lands a few carts later
        # still: on ten carts it reads "8 of 10". What keeps that honest is the packing left to
        # do, about 44 ms a cart against a round trip of a few, so the bar is up and counting
        # well before this looks. A fixture small enough to finish packing first would flake.
        packing = page.eval(
            "!document.getElementById('progress').hidden && "
            "document.getElementById('progress-text').textContent"
        )
        if not packing or 'Packing' not in packing:
            sys.exit(f'the progress bar was not showing a packing count: {packing!r}')
        print(f'asserted: progress bar reads {packing!r} mid-write')
        wait(lambda: (downloads / 'labels.zip').exists(), 60, 'labels.zip to download')
        time.sleep(1)
        shutil.copy(downloads / 'labels.zip', OUT / 'labels.zip')
        subprocess.run(['unzip', '-t', str(OUT / 'labels.zip')], check=True)
        with zipfile.ZipFile(OUT / 'labels.zip') as z:
            in_zip = z.read('Labels/cart_shell.ini').decode() if 'Labels/cart_shell.ini' in z.namelist() else None
        if in_zip != f'Drill Dozer = {presets[5]}\n':
            sys.exit(f'the zip for a card with no cart_shell.ini carries {in_zip!r}, not Drill Dozer\'s shell')
        print('asserted: a shell chosen on a card with no cart_shell.ini is in the zip')

        card = OUT / 'card'
        shutil.rmtree(card, ignore_errors=True)
        card.mkdir()
        (card / 'Games').symlink_to(SLOT_GAMES)
        with zipfile.ZipFile(OUT / 'labels.zip') as z:
            z.extractall(card)
        print('labels unzipped into', card)

        print('second pass: a card that already has labels for', ', '.join([*KEPT, PROBE]))
        kept = {nfc(stem) for stem in [*KEPT, PROBE]}
        # Which folder each cart's label belongs in, for the zip's entry names.
        where = {nfc(Path(g).stem): Path(g).parent.name for g in games}
        where[nfc(PROBE)] = 'GBA'
        # What the plain card actually settled on, rather than an assumption that every cart finds
        # a logo: the Game Boy carts here are homebrew and libretro has nothing for them.
        expected = {nfc(s['stem']): s['state'] for s in first} | {stem: 'has-label' for stem in kept}
        states = open_card(page, 'labelled', stage_labelled(stage / 'labelled', games))
        page.shot(1280, OUT / 'studio-labelled-1280.png')
        got = {nfc(s['stem']): s['state'] for s in states}
        if got != expected:
            for stem in sorted(got.keys() | expected.keys()):
                if got.get(stem) != expected.get(stem):
                    print(f'  {stem}: expected {expected.get(stem)}, got {got.get(stem)}')
            sys.exit('the labelled card\'s carts are not in the states they should be')

        shells = {nfc(s['stem']): s['shell'] for s in page.eval('window.__studio.shells()')}
        if (shells.get('Catrap (USA)'), shells.get('Tetris Rosy Retrospection')) != (
                'rounded 112233 solid', 'auto 445566 clear'):
            sys.exit(f'the card\'s two cart_shell.ini files were not read: {shells}')
        print('asserted: the card\'s shells were read, Labels/cart_shell.ini over Config\'s')

        not_buttons = page.eval(
            "[...document.querySelectorAll('#grid > .cart')].filter(c => !c.hidden"
            " && (c.querySelector('.edit-open') || c.getAttribute('role') !== 'button' || c.tabIndex !== 0))"
            ".map(c => c.querySelector('.stem').title)"
        )
        if not_buttons:
            sys.exit(f'these carts are not one button that opens the editor: {not_buttons}')
        print('asserted: every cart on the shelf is itself the button that opens the editor, with no ✎')

        chosen = 'Advance Wars'
        presets = [p.split('\t')[1] for p in page.eval('window.__studio.shellPresets()')]
        preset = presets[5]
        page.eval(EDIT % json.dumps(chosen))
        wait(lambda: page.eval('window.__studio.idle()'), 60, f'{chosen} to be looked up for its tiles')
        seen = page.eval(
            "[document.querySelector('#ed-tiles [data-kind=\"card\"]').hidden,"
            " document.querySelector('#ed-tiles [data-kind=\"card\"]').getAttribute('aria-pressed'),"
            " document.getElementById('ed-bg').closest('.prop').hidden,"
            " document.getElementById('ed-shell').disabled]"
        )
        if seen != [False, 'true', True, False]:
            sys.exit(f'{chosen} has its label: On the card should be shown and chosen, Background hidden and Shell on, got {seen}')
        print(f'asserted: {chosen}, whose label is on the card, wears it as On the card and can change its shell')

        was = shell_of(page, chosen)
        pick_shell(page, 5)
        pick_shell(page, 7)
        page.eval("document.getElementById('ed-undo').click()")
        after_undo = shell_of(page, chosen)
        page.eval("document.getElementById('ed-redo').click()")
        after_redo = shell_of(page, chosen)
        page.eval("document.getElementById('ed-revert').click()")
        after_revert = shell_of(page, chosen)
        if (after_undo, after_redo, after_revert) != (presets[5], presets[7], was):
            sys.exit(f'Undo, Redo, Revert gave {after_undo!r}, {after_redo!r}, {after_revert!r}')
        print('asserted: Undo and Redo step through the shells, and Revert puts back what the cart had')

        before = page.eval(CART_CANVAS % json.dumps(chosen))
        pick_shell(page, 5)
        wait(lambda: page.eval(CART_CANVAS % json.dumps(chosen)) != before, 10, f'{chosen} to redraw in its shell')
        in_panel = page.eval("document.getElementById('ed-face').toDataURL()")
        on_card = page.eval(CART_CANVAS % json.dumps(chosen))
        page.eval('window.scrollTo(0, 0)')
        page.shot(1280, OUT / 'studio-editor-1280.png')
        page.shot(400, OUT / 'studio-editor-400.png')
        overflow = page.eval('document.documentElement.scrollWidth')
        if overflow > 400:
            sys.exit(f'the editor scrolls sideways at 400 px ({overflow})')
        inside = page.eval("(e => e.scrollWidth - e.clientWidth)(document.getElementById('editor'))")
        if inside > 0:
            sys.exit(f'the editor\'s own contents scroll sideways at 400 px, by {inside}')
        page.width(1280)
        page.eval("document.getElementById('ed-close').click()")
        if shell_of(page, chosen) != preset:
            sys.exit('closing the editor lost the shell')
        if in_panel != on_card:
            sys.exit(f'the editor does not show {chosen} as its card does')
        print(f'asserted: the editor shows {chosen} as its card does, and the shell stays when it closes')

        page.eval(EDIT % json.dumps('Catrap (USA)'))
        page.eval("document.getElementById('ed-shell').click()")
        page.eval("document.getElementById('ed-shell-auto').click()")
        page.eval("document.getElementById('ed-close').click()")

        # Replace: a label already on the card, redone on purpose.
        replaced = 'Metroid Fusion'
        page.eval(EDIT % json.dumps(replaced))
        wait(lambda: page.eval('window.__studio.idle()'), 120, f'{replaced} to be looked up for its tiles')
        before = page.eval("[!document.querySelector('#ed-tiles [data-kind=\"card\"]').hidden, "
                           "document.querySelector('#ed-tiles [data-kind=\"card\"]').getAttribute('aria-pressed'), "
                           "document.getElementById('ed-bg').closest('.prop').hidden]")
        if before != [True, 'true', True]:
            sys.exit(f'{replaced} should offer On the card, chosen, with Background hidden: {before}')
        page.eval("document.querySelector('#ed-tiles [data-kind=\"logo\"]').click()")
        wait(lambda: state_of(page, replaced)['state'] == 'ready', 120, f'{replaced} to be dressed for a new label')
        if page.eval("document.getElementById('ed-bg').closest('.prop').hidden"):
            sys.exit(f'Background is still hidden on {replaced} after Logo Only')
        page.eval("document.getElementById('ed-undo').click()")
        after_undo = state_of(page, replaced)['state']
        page.eval("document.getElementById('ed-redo').click()")
        after_redo = state_of(page, replaced)['state']
        if (after_undo, after_redo) != ('has-label', 'ready'):
            sys.exit(f'Undo and Redo of Replace gave {after_undo!r} and {after_redo!r}')
        page.eval("document.getElementById('ed-close').click()")
        print(f'asserted: Replace turns {replaced}, labelled on the card, back into a cart the studio labels, and undoes')

        kept_downloads = OUT / 'downloads-labelled'
        shutil.rmtree(kept_downloads, ignore_errors=True)
        kept_downloads.mkdir()
        page.width(1280)
        page.send('Page.setDownloadBehavior', behavior='allow', downloadPath=str(kept_downloads))
        page.eval("document.getElementById('write').click()")
        wait(lambda: (kept_downloads / 'labels.zip').exists(), 60, 'the labelled card\'s labels.zip to download')
        time.sleep(1)
        with zipfile.ZipFile(kept_downloads / 'labels.zip') as z:
            entries = {nfc(name) for name in z.namelist()}
        overwritten = sorted(entries & {f'Labels/{where[stem]}/{stem}.png' for stem in kept if stem != replaced})
        if overwritten:
            sys.exit(f'labels.zip would overwrite labels the card already has: {overwritten}')
        ready = {
            f'Labels/{where[stem]}/{stem}.png'
            for stem, state in expected.items()
            if state == 'ready'
        } | {f'Labels/{where[replaced]}/{replaced}.png'}
        if entries != ready | {'Labels/cart_shell.ini'}:
            sys.exit(f'labels.zip holds {sorted(entries)}, not the ready carts and Labels/cart_shell.ini')
        with zipfile.ZipFile(kept_downloads / 'labels.zip') as z:
            written = z.read('Labels/cart_shell.ini').decode()
        want = LABEL_SHELLS_ON_CARD + f'{chosen} = auto {preset.split(" ", 1)[1]}\n' + 'Catrap (USA) = auto\n'
        if written != want:
            sys.exit(f'Labels/cart_shell.ini came out as {written!r}, not {want!r}')
        print(f'asserted: Labels/cart_shell.ini keeps its lines, adds {chosen}\'s shell, and masks Catrap\'s Config choice with auto')
        print(f'asserted: {len(kept)} carts are has-label: {", ".join(sorted(kept))}')
        print(f'asserted: the other {len(ready)} carts are ready')
        print(f'asserted: labels.zip holds exactly those {len(ready)}, and none of the {len(kept)} with labels')

        # Third pass: the way Chrome writes, straight to the card through a directory handle, on a
        # card built in the page's private file system. Nothing else here ever writes in place.
        direct = [g for g in games if g.startswith('GBA/')][:3]
        if not any(nfc(Path(g).stem) == 'Advance Wars' for g in direct):
            direct = [g for g in games if nfc(Path(g).stem) == 'Advance Wars'] + direct[:2]
        page.eval("""(async games => {
          const root = await navigator.storage.getDirectory();
          for await (const name of root.keys()) await root.removeEntry(name, { recursive: true });
          const card = await root.getDirectoryHandle('card', { create: true });
          const into = await card.getDirectoryHandle('Games', { create: true });
          for (const g of games) {
            const [platform, file] = g.split('/');
            const dir = await into.getDirectoryHandle(platform, { create: true });
            const out = await (await dir.getFileHandle(file, { create: true })).createWritable();
            await out.write(await (await fetch('/card/Games/' + g.split('/').map(encodeURIComponent).join('/'))).blob());
            await out.close();
          }
          await window.__studio.openDirectory(card);
          return true;
        })(%s)""" % json.dumps(direct))
        wait(lambda: page.eval('window.__studio.idle()'), 600, 'the directory card to be looked up')
        if page.eval("document.getElementById('fill').open"):
            page.eval("document.getElementById('fill-logo').click()")
            wait(lambda: page.eval('window.__studio.idle()'), 600, 'the directory card to be filled')
        page.eval(EDIT % json.dumps('Advance Wars'))
        pick_shell(page, 5)
        page.eval("document.getElementById('ed-close').click()")
        page.eval("document.getElementById('write').click()")
        wait(lambda: page.eval('window.__studio.idle()'), 60, 'the write to the card to finish')
        if not page.eval("document.getElementById('banner').hidden"):
            sys.exit(f'the write raised a banner: {page.eval("document.getElementById(\'banner\').textContent")!r}')
        on_card = page.eval("""(async () => {
          const card = await (await navigator.storage.getDirectory()).getDirectoryHandle('card');
          const read = async (...path) => {
            let dir = card;
            for (const p of path.slice(0, -1)) dir = await dir.getDirectoryHandle(p);
            return (await (await dir.getFileHandle(path.at(-1))).getFile()).size;
          };
          const shells = await (async () => {
            try {
              const dir = await card.getDirectoryHandle('Labels');
              return await (await (await dir.getFileHandle('cart_shell.ini')).getFile()).text();
            } catch (e) { return 'missing: ' + e.name; }
          })();
          let label = 0;
          try { label = await read('Labels', 'GBA', 'Advance Wars.png'); } catch (e) { label = -1; }
          return { shells, label };
        })()""")
        want = f'Advance Wars = {presets[5]}\n'
        if on_card['shells'] != want or on_card['label'] <= 0:
            sys.exit(f'writing to the card gave cart_shell.ini {on_card["shells"]!r} and a label of '
                     f'{on_card["label"]} bytes, not {want!r} and a label')
        print('asserted: Write to card puts the label and the chosen shell on the card itself')

        # Replace on that card: the label the write just made is written over, not skipped.
        digest = """(async () => {
          const card = await (await navigator.storage.getDirectory()).getDirectoryHandle('card');
          const dir = await (await card.getDirectoryHandle('Labels')).getDirectoryHandle('GBA');
          const bytes = await (await (await dir.getFileHandle('Advance Wars.png')).getFile()).arrayBuffer();
          return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(b => b.toString(16)).join('');
        })()"""
        was = page.eval(digest)
        page.eval(EDIT % json.dumps('Advance Wars'))
        wait(lambda: page.eval('window.__studio.idle()'), 120, 'Advance Wars to be looked up for its tiles')
        page.eval("document.querySelector('#ed-tiles [data-kind=\"logo\"]').click()")
        wait(lambda: state_of(page, 'Advance Wars')['state'] == 'ready', 120, 'Advance Wars to be dressed for a new label')
        background(page, '#123456')
        page.eval("document.getElementById('ed-close').click()")
        page.eval("document.getElementById('write').click()")
        wait(lambda: page.eval('window.__studio.idle()'), 60, 'the replacing write to finish')
        if page.eval(digest) == was or not page.eval("document.getElementById('banner').hidden"):
            sys.exit('Replace did not write over the label on the card')
        if state_of(page, 'Advance Wars')['state'] != 'has-label':
            sys.exit('after the replacing write, Advance Wars is not back to showing its label on the card')
        print('asserted: Replace writes over the label on the card, and the cart shows it after')
    finally:
        chrome.terminate()
        server.shutdown()
        shutil.rmtree(stage, ignore_errors=True)
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == '__main__':
    main()
