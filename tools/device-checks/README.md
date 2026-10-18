# Device Helper Scripts

Manual inspection and file-sync utilities for local device testing. Outputs are written to
`outputs/manual/` so release archives remain untouched.

| Script | Purpose |
| --- | --- |
| `capture_cartridge_motion.py` | Record and screenshot cartridge insertion and ejection without changing display settings |
| `capture_swipe_trigger.py` | Record swipe insertion/ejection and verify system Home gesture navigation |
| `capture_letter_hint_y700.py` | Capture screenshots of letter-jump hints |
| `sync_labels_y700.py` | Sync and verify cartridge labels, backing up differing files before replacement |
| `push_gba_samples.py` | Push a local GBA batch while refusing to overwrite differing existing files |

These scripts keep their local ADB path and device serial defaults; inspect and adjust them for the
connected device before running. The sync scripts read the untracked local `Slot/` folder and touch
files on the device, so do not use them to generate test fixtures.

Recording scripts start the application and send input events. Run them only during authorized
manual device checks.

## Artwork Cache and Slot Manager Suites

After installing the application and androidTest APKs, run:

```powershell
adb shell am instrument -w -e suite artwork fyi.slot.android.test/fyi.slot.android.InteractionChecks
adb shell am instrument -w -e suite studio fyi.slot.android.test/fyi.slot.android.InteractionChecks
```

`artwork` verifies preemption, early label publishing, concurrent recovery when clearing caches,
idle prewarming, and queue/memory bounds on a 1,000-game index. `studio` verifies direct single-cart
opening, compact layouts, back navigation, save/discard flows, lifecycle cleanup, and bilingual
controller navigation using isolated fixtures before restoring user preferences.
