"""Check real touch swipes and system Home without changing display configuration."""
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "outputs" / "manual"
ADB = "C:/Users/theoc/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "adb-HA2E9THE-MznIbQ._adb-tls-connect._tcp"
REMOTE = "/sdcard/slot-swipe-trigger.mp4"


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], check=True, capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def snapshot(name):
    result = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                            check=True, capture_output=True)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / name).write_bytes(result.stdout)


prefs = adb("shell", "run-as", "fyi.slot.android", "cat", "shared_prefs/interaction.xml")
if '<boolean name="swipe_to_insert" value="true"' not in prefs:
    raise RuntimeError("Select swipe mode first; this check does not modify preferences.")
if "Override size:" in adb("shell", "wm", "size"):
    raise RuntimeError("Display override present; do not modify it during this check.")

adb("shell", "am", "force-stop", "fyi.slot.android")
adb("shell", "am", "start", "-n", "fyi.slot.android/.MainActivity")
time.sleep(8)
windows = adb("shell", "dumpsys", "window")
if "mDisplayFrame=Rect(0, 0 - 3040, 1904)" not in windows:
    raise RuntimeError("Expected native Y700 landscape dimensions.")
snapshot("swipe-trigger-shelf.png")
recording = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--size", "960x600",
                              "--bit-rate", "6000000", "--time-limit", "9", REMOTE],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(1)
adb("shell", "input", "swipe", "1520", "800", "1520", "1150", "1000")
time.sleep(1.5)
snapshot("swipe-trigger-seated.png")
adb("shell", "input", "swipe", "1520", "1100", "1520", "750", "1000")
time.sleep(1.8)
stdout, stderr = recording.communicate(timeout=20)
if recording.returncode:
    raise RuntimeError(stderr.decode("utf-8", errors="replace"))
print(adb("pull", REMOTE, str(OUTPUT / "swipe-trigger-device.mp4")))

# Fullscreen navigation: reveal system bars, then swipe Home while they are visible.
adb("shell", "input", "swipe", "1520", "1903", "1520", "1120", "250")
time.sleep(.3)
adb("shell", "input", "swipe", "1520", "1903", "1520", "1120", "250")
time.sleep(1)
focus = "\n".join(line for line in adb("shell", "dumpsys", "window").splitlines()
                  if "mCurrentFocus" in line or "mFocusedApp" in line)
print(focus)
(OUTPUT / "swipe-trigger-system-navigation.txt").write_text(focus, encoding="utf-8")
if "com.zui.launcher" not in focus:
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    raise RuntimeError("Gesture Home failed; returned with system Home key instead.")
print("Swipe recording and gesture Home verified; display configuration unchanged.")
