# Slot Manager

Slot Manager handles game transfers, library management, cartridge labels, and box art covers for
Slot for Android. It runs directly on the device or in a desktop/mobile browser over the local
network.

This directory is a fork of upstream [Cart Studio](https://github.com/BrandonKowalski/slot-cart-studio)
synced to commit `f40b6801a36007874a8334cbd02180d3059c66e0`, built against upstream
[slot](https://github.com/BrandonKowalski/slot) commit `34ac38031fd903eb2a0a0157f1dea4d3a5546195`
(v1.7.0) as pinned in `slot.ref`. It is licensed under GPL-3.0-or-later; see `LICENSE`.

Prebuilt web and WASM assets are committed under `app/src/main/assets/studio/`, so normal Gradle
builds do not require Rust, `wasm-pack`, or Node.js.

## Changes from Upstream Cart Studio

GitHub Pages deployment files (`.github/`, `CNAME`), SD-card eject prompts, global card writes,
ZIP downloads, and the build footer were removed. The Android/device fork adds:

1. **Device data source (`web/device.js`)**: talks to the handheld's HTTP API for library listings,
   ROM headers, CRC32 checksums, shell configuration (`Config/cart_shell.txt` and
   `Labels/cart_shell.txt`), labels, box art, game uploads, and game deletion. Pairing codes are
   stored only in `sessionStorage` for the active browser tab.
2. **Header and CRC on device (`web/studio.js`)**: identifies games through the device's `head()`
   and `crc()` endpoints without transferring full ROM files over the network, and proxies remote
   artwork requests through `/art/` on the handheld.
3. **v1.7.0 layered cartridge preview (`src/face.rs`, `src/composite.rs`)**: composites the interior
   plastic backing, PCB, and painted front shell in the same order as the Android shelf renderer,
   preserving transparent backgrounds.
4. **Box art management (`web/boxart.js`)**: fetches matched covers from `libretro-thumbnails`,
   crops custom images to a centered square PNG, and writes or removes `Boxart/<platform>/<stem>.png`
   without overwriting existing covers unless chosen in the editor.
5. **Per-game modal editor (`web/editor.js`, `web/save.js`, `web/history.js`, `web/decision.js`)**:
   replaces global writes and undo/redo with per-game Save and Reset baselines, unsaved-change
   prompts, independent label and cover switches, and unified batch completion for missing labels
   and matched covers.
6. **Smarter fallback matching and lazy rendering (`web/matcher.js`, `web/studio.js`)**: when an
   exact CRC32 is changed by a translation or patch, conservatively scores No-Intro `.dat` entries
   by normalized title, region, revision, version, and 4-byte GBA game code; strips bracketed tags
   from default search queries while keeping subtitles; and uses `IntersectionObserver` so only
   visible or active cartridges run WASM compositing.
7. **On-device mode (`web/local-mode.js`)**: binds a loopback-only session with a per-launch token,
   supports single-cartridge direct editing (`?local=1&platform=...&file=...`), integrates native
   Android image picking for custom logos/labels/covers, and routes hardware Back through open
   popovers and unsaved-change prompts before closing.
8. **Localization (`web/i18n.js`)**: provides a Simplified Chinese interface alongside English and
   follows the active Slot language when opened on the device.

## Rebuilding Assets

Rebuilding the WASM package requires Rust 1.96+ with `wasm32-unknown-unknown`, `wasm-pack` 0.15.0,
Git, and Node.js:

```powershell
powershell -ExecutionPolicy Bypass -File tools/cart-studio/android-build.ps1 -WasmPack <path-to-wasm-pack> -Test
```

The script checks out `slot` at `slot.ref` inside `work/studio-build/`, runs
`wasm-pack build --release --target web`, executes the Node test suite when `-Test` is passed, and
syncs `web/` (excluding `*.test.mjs`) to `app/src/main/assets/studio/`.

When only JS, HTML, or CSS files under `web/` change, copy the updated files to
`app/src/main/assets/studio/` without rebuilding WASM.

## Verification

```powershell
node --test tools/cart-studio/web/*.test.mjs
node tools/cart-studio/tools/verify-editor.cjs
node tools/cart-studio/tools/verify-preview.mjs
```

`verify-editor.cjs` uses Playwright against an offline synthetic library server and never touches
user files (`SLOT_PLAYWRIGHT`, `SLOT_CHROMIUM`, and `SLOT_SCREENSHOTS` are optional). `verify-preview.mjs`
compares WASM output against the seven Rust pixel references shared with the Android instrumentation
suite.
