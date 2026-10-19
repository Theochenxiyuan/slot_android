"""Sync local label assets, preserving different device versions in a backup."""
import hashlib
import json
import shlex
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "outputs" / "manual"
LOCAL = ROOT / "Slot" / "Labels"
REMOTE = "/storage/emulated/0/Slot/Labels"
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


def inventory():
    output = adb("shell", f"find {shlex.quote(REMOTE)} -type f -exec sha256sum {{}} +")
    return {name.strip()[len(REMOTE) + 1:]: checksum.lower()
            for checksum, name in (line.split(None, 1) for line in output.splitlines())}


def main():
    existing = inventory()
    local = {path.relative_to(LOCAL).as_posix(): (path, digest(path))
             for path in sorted(LOCAL.rglob("*")) if path.is_file()}
    if not local:
        raise RuntimeError("No local labels to sync")
    added, updated, skipped = [], [], []
    for relative, (path, checksum) in local.items():
        target = REMOTE + "/" + relative
        if existing.get(relative) == checksum:
            skipped.append(relative)
            continue
        if relative in existing:
            backup = ROOT / "outputs" / "labels-y700-backup" / relative
            backup.parent.mkdir(parents=True, exist_ok=True)
            if backup.exists():
                raise RuntimeError(f"Backup already exists; refusing overwrite: {relative}")
            adb("pull", target, str(backup))
            if digest(backup) != existing[relative]:
                raise RuntimeError(f"Backup verification failed: {relative}")
        parent, basename = target.rsplit("/", 1)
        temporary = parent + "/." + basename + ".slot-upload"
        adb("shell", "mkdir -p " + shlex.quote(parent))
        adb("shell", "test ! -e " + shlex.quote(temporary))
        adb("push", str(path), temporary)
        transferred = adb("shell", "sha256sum " + shlex.quote(temporary)).split(None, 1)[0].lower()
        if transferred != checksum:
            raise RuntimeError(f"Label checksum mismatch: {relative}")
        if relative in existing:
            current = adb("shell", "sha256sum " + shlex.quote(target)).split(None, 1)[0].lower()
            if current != existing[relative]:
                raise RuntimeError(f"Device label changed during transfer: {relative}")
            updated.append(relative)
        else:
            adb("shell", "test ! -e " + shlex.quote(target))
            added.append(relative)
        adb("shell", "mv " + shlex.quote(temporary) + " " + shlex.quote(target))
    actual = inventory()
    for relative, (_, checksum) in local.items():
        if actual.get(relative) != checksum:
            raise RuntimeError(f"Final label verification failed: {relative}")
    labels = {path.stem for path in (LOCAL / "GBA").glob("*.png")}
    missing = [path.name for path in (ROOT / "Slot" / "GBA").glob("*.gba") if path.stem not in labels]
    report = {"serial": SERIAL, "destination": REMOTE, "added": added, "updated": updated, "skipped_identical": skipped,
              "verified_sha256": {relative: checksum for relative, (_, checksum) in local.items()}, "missing_gba_labels": missing}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "labels-y700-transfer.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"added": len(added), "updated": len(updated), "skipped_identical": len(skipped),
                      "verified": len(local), "png_labels": len(labels), "missing_gba_labels": missing}, ensure_ascii=False))


if __name__ == "__main__":
    main()
