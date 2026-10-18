# Third-Party Notices and Attribution

Slot for Android is licensed under the GNU General Public License v3.0 or later (`GPL-3.0-or-later`).
The full license text is in [LICENSE](LICENSE).

## Upstream Projects

### slot (`BrandonKowalski/slot`)

- Upstream repository: [https://github.com/BrandonKowalski/slot](https://github.com/BrandonKowalski/slot)
- License: `GPL-3.0-or-later`
- Pinned commits used during port and asset generation:
  - `a54157c0b8db2e011b7f822c41786842209aa3c7` (initial silhouettes, audio clips, INI rules, and motion timing)
  - `34ac38031fd903eb2a0a0157f1dea4d3a5546195` (`v1.7.0` painted cartridge textures, internal layers, shell presets, and shelf spacing)

Slot for Android ports and adapts the following upstream components to native Java/Android and the
bundled Rust asset tools:

- **Cartridge silhouettes and painted textures**: `cart.svg`, `gb_cart.svg`, `gbc_cart.svg`, and the
  `v1.7.0` hand-painted shell/interior/PCB textures (`gba_neutral.png`, `gb_neutral.png`,
  `gbc_neutral.png`, `gba_clear.png`, `gbc_clear.png`, `gba_interior.png`, `gb_interior.png`,
  `gbc_interior.png`, `board.png`, `gb_board.png`) created by Markus
  ([@MarkusMaribu](https://github.com/MarkusMaribu), upstream PR #74). Source files are kept in
  `tools/cart-assets/assets/`; preprocessed runtime masks and textures are in
  `app/src/main/assets/cartridges/`.
- **Cartridge rendering and shell configuration**: `CartridgeRenderer.java`, `ShellConfig.java`, and
  `RomRecord.java` port the upstream `Overlay` shell tinting, 80% translucent shells, deterministic
  glitter highlights, internal layer clipping, GB header inspection, automatic shell palette table,
  and two-layer `cart_shell.txt` rules from `slot-ui` and `slot-store`.
- **Mechanical motion and slot geometry**: `CartridgeMotion.java`, `SlotView.java`,
  `MachineRenderer.java`, `SlotOpening.java`, and `BackdropRenderer.java` adapt the 450 ms
  three-stage insertion curve, lip scale reduction, quintic easing, scoop opening, and backdrop
  dimming from `slot` and `slot-ui`.
- **Audio cues**: `tools/audio-assets/insert.pcm` and `eject.pcm` come from `crates/slot/assets/`.
  `tools/audio-assets/import_audio.py` wraps them losslessly into `app/src/main/res/raw/insert.wav`
  and `eject.wav`, and `CartridgeAudio.java` preserves the 97 ms / 21 ms lead timing from
  `crates/slot/src/audio/sfx.rs`.

### Cart Studio (`BrandonKowalski/slot-cart-studio`)

- Upstream repository: [https://github.com/BrandonKowalski/slot-cart-studio](https://github.com/BrandonKowalski/slot-cart-studio)
- License: `GPL-3.0-or-later`
- Pinned upstream commit: `f40b6801a36007874a8334cbd02180d3059c66e0`

`tools/cart-studio/` is our fork of Cart Studio, presented in the product as **Slot Manager** after
adding game transfers, library deletion, box art covers, on-device editing, fallback ROM matching,
and bilingual UI. Prebuilt web and WASM assets under `app/src/main/assets/studio/` are compiled from
`tools/cart-studio/` and upstream `slot` (`34ac38031fd903eb2a0a0157f1dea4d3a5546195`). See
[tools/cart-studio/ANDROID.md](tools/cart-studio/ANDROID.md) for a detailed list of modifications.

### Architectural Reference: Cannoli

- Repository: `Cannoli` (`7755e376b6add0bbd25ea19104c368b7a40b979f`)

Directory discovery, background scanning, debounced file watching, and the separation between the
local HTTP server, foreground service, and pairing manager were informed by Cannoli's structure.
All Android Java code in this repository is an independent implementation.

## Fonts

- `app/src/main/assets/label.ttf` is distributed from upstream `slot` under the SIL Open Font
  License 1.1. The full license text is in `app/src/main/assets/label.ttf.OFL.txt`.

## Runtime Libraries

The Android application includes the following open-source libraries at runtime:

- **NanoHTTPD** (`org.nanohttpd:nanohttpd:2.3.1`) —
  [https://github.com/NanoHttpd/nanohttpd](https://github.com/NanoHttpd/nanohttpd), BSD-3-Clause.
- **QR Code generator library** (`io.nayuki:qrcodegen:1.8.0`) —
  [https://github.com/nayuki/QR-Code-generator](https://github.com/nayuki/QR-Code-generator), MIT.

## Build, Test, and WASM Dependencies

- **Gradle Wrapper** (`gradle/wrapper/`) — Apache-2.0.
- **JUnit 4** (`junit:junit:4.13.2`, EPL-1.0) and **Robolectric** (`org.robolectric:robolectric:4.16.1`,
  MIT) are used only by `testImplementation` and are not packaged into release APKs.
- **Rust crates in `tools/cart-assets/` and `tools/cart-studio/`**: `resvg` / `usvg` 0.48, `png` 0.17,
  `fontdue` 0.9, `crc32fast`, `unicode-normalization`, `wasm-bindgen`, and transitive dependencies
  locked in each directory's `Cargo.lock`. `tools/cart-assets/` runs only on the development machine;
  `tools/cart-studio/` compiles to `app/src/main/assets/studio/pkg/slot_cart_studio_bg.wasm`.

## Online Databases and External Emulators

- **Game metadata and artwork fetched at runtime by Slot Manager** are not bundled into the APK:
  - Game titles and CRC catalogs from [libretro-database](https://github.com/libretro/libretro-database)
    (`CC BY-SA 4.0`).
  - Box art thumbnails from [libretro-thumbnails](https://github.com/libretro-thumbnails).
  - Cartridge logos and scanned label textures from `art.slot-cfw.fyi` (sourced from
    [ScreenScraper](https://www.screenscraper.fr/), `CC BY-NC-SA 4.0`).
- **RetroArch** ([https://github.com/libretro/RetroArch](https://github.com/libretro/RetroArch),
  `GPL-3.0-or-later`) and Libretro cores (such as mGBA, VBA-M, gpSP, Gambatte, and SameBoy) are
  installed separately by the user and are never bundled into Slot for Android.
- No commercial ROMs or proprietary BIOS files are included in this repository or in the APK.
- Game Boy and Game Boy Advance are trademarks of Nintendo. This project is not affiliated with or
  endorsed by Nintendo.
