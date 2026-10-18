# Original Cartridge Audio

`insert.pcm` and `eject.pcm` come from `crates/slot/assets/` in [BrandonKowalski/slot](https://github.com/BrandonKowalski/slot) at commit `a54157c0b8db2e011b7f822c41786842209aa3c7`. They are licensed under GPL-3.0-or-later; see the root `THIRD_PARTY.md` and `LICENSE`.

Both clips are 48 kHz, 16-bit signed little-endian, mono PCM, with durations of 240 ms and 315 ms. From the repository root, run:

```text
python tools/audio-assets/import_audio.py
```

The script wraps the raw clips into `app/src/main/res/raw/insert.wav` and `eject.wav`, checks the SHA-256 of the source files, and verifies that the unpacked WAV sample bytes match the originals. Normal Android builds consume the committed WAV files directly.

`CartridgeAudio.java` preserves the 97 ms / 21 ms lead times from `crates/slot/src/audio/sfx.rs`. `SlotView.java` connects that timing to the three-stage motion in `CartridgeMotion`: the insertion clip starts before seating, or immediately when a dragged cartridge is released near the bottom, while keeping the seated hold. The ejection clip starts before reverse travel; demo cartridges wait for the scene to fade out, while real ROM cartridges start the audio lead directly. Cancelled drags stay silent, and pausing stops active playback without replaying old clips on resume.
