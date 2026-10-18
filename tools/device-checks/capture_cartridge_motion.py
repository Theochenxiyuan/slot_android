"""Record the connected tablet's current cartridge without changing its library or preferences."""
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "outputs" / "manual"
ADB = "C:/Users/theoc/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "adb-HA2E9THE-MznIbQ._adb-tls-connect._tcp"
REMOTE_VIDEO = "/sdcard/slot-animation-trial.mp4"


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], check=True, capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def screenshot(name):
    data = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                          check=True, capture_output=True).stdout
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / name).write_bytes(data)


def capture():
    # Keep the tablet's display configuration intact: size overrides can disrupt ZUI's taskbar.
    adb("shell", "am", "force-stop", "fyi.slot.android")
    adb("shell", "am", "start", "-n", "fyi.slot.android/.MainActivity")
    # Lenovo's gaming assistant briefly overlays launch notifications.
    time.sleep(8)
    screenshot("motion-shelf.png")
    recording = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--size", "960x600",
                                  "--bit-rate", "6000000", "--time-limit", "9", REMOTE_VIDEO],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1)
    adb("shell", "input", "keyevent", "KEYCODE_BUTTON_A")
    time.sleep(1.5)
    screenshot("motion-seated.png")
    adb("shell", "input", "keyevent", "KEYCODE_BUTTON_B")
    time.sleep(1.5)
    adb("shell", "input", "keyevent", "KEYCODE_BUTTON_A")
    time.sleep(1.5)
    adb("shell", "input", "keyevent", "KEYCODE_BUTTON_B")
    stdout, stderr = recording.communicate(timeout=20)
    if recording.returncode:
        raise RuntimeError(stderr.decode("utf-8", errors="replace"))
    print(adb("pull", REMOTE_VIDEO, str(OUTPUT / "motion-device.mp4")))
    print("Recorded without display overrides; cartridge identity and preferences preserved.")


if __name__ == "__main__":
    capture()
