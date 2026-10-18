"""Wrap Slot's original 48 kHz mono s16le PCM in WAV without changing samples."""
from pathlib import Path
import argparse
import hashlib
import wave

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
HASHES = {
    'insert': 'd418439f5624860cc7dd3393f736551113e16ae674103b7c4b43a71215a15db1',
    'eject': '932dd4df78c8ec8aa3ac303fe8d360e3aad7fadbad81fa9deed46bf45dfa5b7d',
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, help='Optional original Slot checkout to import from')
    args = parser.parse_args()
    output = ROOT / 'app/src/main/res/raw'
    output.mkdir(parents=True, exist_ok=True)
    for name, expected in HASHES.items():
        source = HERE / f'{name}.pcm'
        if args.upstream:
            candidate = args.upstream / 'crates/slot/assets' / source.name
            pcm = candidate.read_bytes()
        else:
            pcm = source.read_bytes()
        if hashlib.sha256(pcm).hexdigest() != expected:
            raise ValueError(f'{name}: original PCM checksum mismatch')
        if args.upstream:
            source.write_bytes(pcm)
        target = output / f'{name}.wav'
        with wave.open(str(target), 'wb') as wav:
            wav.setparams((1, 2, 48000, 0, 'NONE', 'not compressed'))
            wav.writeframes(pcm)
        with wave.open(str(target), 'rb') as wav:
            assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 2, 48000)
            assert wav.readframes(wav.getnframes()) == pcm
        print(f'{name}: {len(pcm) // 2} unchanged samples, {len(pcm) / 96:.0f} ms')

if __name__ == '__main__':
    main()
