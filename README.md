# Slot for Android

A skeuomorphic cartridge launcher and optional home screen for Android 12+ handhelds, tablets, and phones. Slot for Android brings the painted cartridges, physical slot mechanics, and shelf browsing of [slot](https://github.com/BrandonKowalski/slot) (`v1.7.0`) to Android, paired with **Slot Manager** for on-device and browser-based game transfers, cartridge labels, shells, and box art, plus external **RetroArch** launching.

## Download and Setup

1. Download the APK from the [latest release](https://github.com/Theochenxiyuan/slot_android/releases/latest) and install it on an Android 12+ device. Allow installation from your browser or file manager if Android asks.
2. Open Slot, allow file access when prompted, and select your existing `Slot/` or `Roms/` library folder. You can also select an empty folder and import `.gba`, `.gb`, or `.gbc` games.
3. Install [RetroArch](https://www.retroarch.com/?page=platforms) separately. In RetroArch, open **Online Updater > Core Downloader** and install **mGBA** for GBA, GBC, and GB, or choose another supported core in Slot.
4. Insert a cartridge to launch its game. Optionally set Slot as your default home screen from **Settings > System**.

Games, BIOS files, RetroArch, and emulator cores are not included. Development APKs use a different signing key; uninstall an existing debug build before installing the public release, saving any app-specific settings you want to keep first.

## Highlights

- **Tactile Cartridge Shelf**: Browse GBA, GBC, and GB games on a circular horizontal shelf with hand-painted shells, transparent internal PCBs, glitter finishes, and authentic insertion/ejection motion and audio cues.
- **Touch, Controller, and Keyboard Input**: Swipe or drag cartridges into the slot by touch, or navigate with a gamepad or keyboard (`LB`/`RB` or `L1`/`R1` for categories, `R3` for the category wheel, D-pad up/down for initial-letter jumps, `Select`/`Start` for context menus, and configurable Xbox / Nintendo / PlayStation button icons).
- **Seated Cartridge View & External RetroArch Launch**: Seating a real ROM launches RetroArch with a non-destructive `.slot-retroarch.cfg` wrapper that inherits your existing `retroarch.cfg`. Returning to Slot leaves the cartridge seated with its box art (or label), ready to relaunch or eject.
- **Per-Game Cores & Shelf Collections**: Choose a global core per platform or override the core for individual games (`mGBA`, `VBA-M`, `gpSP`, `Gambatte`, `SameBoy`). Pin games to **Favorites** and revisit recently played titles in **Recents**.
- **Slot Manager (On Device & Over LAN)**: Edit a single cartridge's appearance directly from its shelf menu, or open the full library on the device or from a desktop browser via a 6-character pairing code. Upload `.gba`/`.gb`/`.gbc` ROMs, batch-fill missing labels and matched covers, pick custom images, or delete games while keeping save files intact.
- **Built-In App Drawer & Customization**: Slide the housing up to reveal the battery-bay settings and app drawer. Set Slot as the default Android home screen, switch between dark and light housing themes, crop custom wallpapers, and use the interface in English or Simplified Chinese.

## Library Layout

Slot uses a single library root (such as `/storage/emulated/0/Slot`) with case-insensitive platform folders:

- `Games/GBA/*.gba`
- `Games/GB/*.gb|*.gbc`
- `Games/GBC/*.gb|*.gbc`
- `Labels/<platform>/<ROM stem>.png`
- `Boxart/<platform>/<ROM stem>.png`
- `Labels/cart_shell.txt` and `Config/cart_shell.txt`

Existing `Roms` or `Emulation/Roms` folders are also supported: Slot scans the platform directories in place and stores its labels, covers, and shell settings in a sibling `Slot/` directory on the same storage volume.

## Requirements

- **Android 12+** (API 31+), landscape and square displays from `1:1` to `16:9` (centered on wider or narrower screens).
- **RetroArch** (`com.retroarch.aarch64` or `com.retroarch`) installed separately by the user, with the desired GBA / GBC / GB Libretro cores downloaded inside RetroArch's Online Updater.

## Building from Source

Requires **JDK 17** and **Android SDK Platform 36**. Prebuilt cartridge masks, audio WAVs, and Slot Manager web/WASM assets are committed in the repository, so a standard Android build does not need Rust or Node.js.

1. Set `sdk.dir` in an untracked `local.properties` file, or open the project in Android Studio.
2. Build the debug APK and run unit tests and Android Lint:

   ```powershell
   .\gradlew.bat testDebugUnitTest assembleDebug lintDebug
   ```

3. Install the debug APK on a connected device:

   ```powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

To rebuild the optional asset pipelines after editing their sources, see:

- [tools/cart-studio/ANDROID.md](tools/cart-studio/ANDROID.md) — Slot Manager web and WASM assets
- [tools/cart-assets/README.md](tools/cart-assets/README.md) — cartridge masks, textures, and Rust pixel references
- [tools/audio-assets/README.md](tools/audio-assets/README.md) — lossless WAV wrapping for insertion/ejection PCM clips

### Release Signing

Keep the release keystore and its passwords outside the repository. Set `SLOT_SIGNING_PROPERTIES` to an external properties file (or pass `-PslotSigningProperties=<absolute-path>`):

```properties
storeFile=slot-release.p12
storePassword=<keystore-password>
keyAlias=slot-release
keyPassword=<key-password>
```

`storeFile` can be absolute or relative to that properties file. Run `./gradlew assembleRelease` (`.\gradlew.bat assembleRelease` on Windows) to produce `app/build/outputs/apk/release/app-release.apk`. Without signing configuration, Release builds remain unsigned; they never fall back to the debug key. Back up the keystore and passwords securely, and reuse the same key for future updates. A release-signed APK cannot replace an existing debug-signed installation without a separate data migration or uninstall.

## Verification

```powershell
# JVM unit tests, debug/test APKs, and Android Lint
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug

# Slot Manager web unit tests
node --test tools/cart-studio/web/*.test.mjs

# Full on-device instrumentation suite (254 checks)
adb shell am instrument -w fyi.slot.android.test/fyi.slot.android.InteractionChecks
```

Individual device suites can be run with `-e suite <name>` (`artwork`, `studio`, `onboarding`, `retroarch`, `retroarch-settings`, `retroarch-presets`, `connection`, `library`, `management`, `language`, `letters`, `drawing`, `animation`, `swipe`) or `-e check <number>`.

## AI Disclosure

AI coding agents were used extensively throughout the development of Slot for Android for Java/Android implementation, Slot Manager web adaptations, asset-pipeline integration, automated test writing, and documentation, under human product direction, review, and on-device verification.

Upstream [slot](https://github.com/BrandonKowalski/slot) and [Cart Studio](https://github.com/BrandonKowalski/slot-cart-studio) also document AI assistance in their own repositories; the painted `v1.7.0` cartridge artwork by Markus ([@MarkusMaribu](https://github.com/MarkusMaribu)) is human-drawn.

## Third-Party Notices & License

Slot for Android is a derivative port based on [BrandonKowalski/slot](https://github.com/BrandonKowalski/slot) and [BrandonKowalski/slot-cart-studio](https://github.com/BrandonKowalski/slot-cart-studio), with painted cartridge textures by [@MarkusMaribu](https://github.com/MarkusMaribu). Full source attribution, dependency licenses (`NanoHTTPD`, `qrcodegen`, `label.ttf`, Rust crates, and online artwork/database sources) are documented in [THIRD_PARTY.md](THIRD_PARTY.md).

Licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`). See [LICENSE](LICENSE) for the full text.

Game Boy and Game Boy Advance are trademarks of Nintendo. This project is not affiliated with or endorsed by Nintendo, Libretro, or RetroArch.
