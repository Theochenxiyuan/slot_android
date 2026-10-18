"""Copy the prepared GBA batch to Y700 without overwriting existing ROMs."""
import hashlib
import json
import shlex
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "outputs" / "manual"
LOCAL = ROOT / "Slot" / "Games" / "GBA"
REMOTE = "/storage/emulated/0/Slot/Games/GBA"
ADB = "C:/Users/theoc/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "HA2E9THE"


def adb(*args):
    result = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout.strip()


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def remote_hashes():
    output = adb("shell", f"find {shlex.quote(REMOTE)} -maxdepth 1 -type f -exec sha256sum {{}} +")
    result = {}
    for line in output.splitlines():
        checksum, filename = line.split(None, 1)
        result[filename.strip().rsplit("/", 1)[-1]] = checksum.lower()
    return result


def main():
    existing = remote_hashes()
    local = {path.name: (path, digest(path)) for path in sorted(LOCAL.glob("*.gba"))}
    if len(local) != 51:
        raise RuntimeError(f"Expected 51 local ROMs, found {len(local)}")
    for name, (_, checksum) in local.items():
        matches = [item for item in existing if item.casefold() == name.casefold()]
        if matches and (len(matches) != 1 or existing[matches[0]] != checksum):
            raise RuntimeError(f"Existing device file differs; refusing overwrite: {name}")
    copied, skipped = [], []
    for name, (path, checksum) in local.items():
        if any(item.casefold() == name.casefold() for item in existing):
            skipped.append(name)
            continue
        target = REMOTE + "/" + name
        temporary = REMOTE + "/." + name + ".slot-upload"
        check = adb("shell", f"if [ -e {shlex.quote(temporary)} ] || [ -e {shlex.quote(target)} ]; then echo occupied; else echo free; fi")
        if check != "free":
            raise RuntimeError(f"Destination became occupied: {name}")
        adb("push", str(path), temporary)
        transferred = adb("shell", "sha256sum " + shlex.quote(temporary)).split(None, 1)[0].lower()
        if transferred != checksum:
            raise RuntimeError(f"Transferred file checksum mismatch; final name not published: {name}")
        adb("shell", f"test ! -e {shlex.quote(target)} && mv {shlex.quote(temporary)} {shlex.quote(target)}")
        copied.append(name)
    verified = remote_hashes()
    for name, (_, checksum) in local.items():
        match = next((item for item in verified if item.casefold() == name.casefold()), None)
        if match is None or verified[match] != checksum:
            raise RuntimeError(f"Final verification failed: {name}")
    report = {"serial": SERIAL, "destination": REMOTE, "copied": copied, "skipped_identical": skipped,
              "verified_sha256": {name: checksum for name, (_, checksum) in local.items()}}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "gba-y700-transfer.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"copied": len(copied), "skipped_identical": len(skipped), "verified": len(local),
                      "device_gba_count": sum(name.lower().endswith(".gba") for name in verified)}, ensure_ascii=False))
    adb("shell", "am", "start", "-n", "fyi.slot.android/.MainActivity")


if __name__ == "__main__":
    main()
