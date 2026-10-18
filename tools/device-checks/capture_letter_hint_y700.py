"""Capture live target feedback immediately after a direction press."""
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "outputs" / "manual"
ADB = "C:/Users/theoc/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "HA2E9THE"


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


OUTPUT.mkdir(parents=True, exist_ok=True)
for name, key in [("down", "KEYCODE_DPAD_DOWN"), ("up", "KEYCODE_DPAD_UP")]:
    started = time.monotonic()
    adb("shell", "input", "keyevent", key)
    adb("shell", "screencap", "-p", "/sdcard/slot-live-letter-hint.png")
    print(name + " captured after " + str(round((time.monotonic() - started) * 1000)) + " ms")
    adb("pull", "/sdcard/slot-live-letter-hint.png", str(OUTPUT / ("slot-live-letter-hint-" + name + ".png")))
