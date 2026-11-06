#!/usr/bin/env python3
"""Fetch Mozilla Common Voice 17.0 Russian clips for the 20-talker mix.

License: CC0-1.0. Mirror: Hugging Face fsicoli/common_voice_17_0 (unofficial
packaging of https://commonvoice.mozilla.org/). We keep the large tar and
unused mp3s out of git (`samples/demo-stress/corpus/` is gitignored).

Selects ≥20 distinct client_id talkers from the official **test** split
(gold `sentence` field). Main talker needs ≥3 clips.

Usage:
  python3 scripts/fetch_cv17_ru.py
  CV17_TAR=/path/to/ru_test_0.tar python3 scripts/fetch_cv17_ru.py
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import random
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "samples" / "demo-stress" / "corpus" / "cv17-ru"
HF = "https://huggingface.co/datasets/fsicoli/common_voice_17_0/resolve/main"
SEED = 20261106
N_TALKERS = 20
MAIN_CLIPS = 3
MIN_MS = 1800
MAX_MS = 9000


def _download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and dest.stat().st_size > 1000:
        print(f"have {dest} ({dest.stat().st_size} bytes)")
        return
    print(f"GET {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    urllib.request.urlretrieve(url, tmp)
    tmp.replace(dest)
    print(f"  wrote {dest} ({dest.stat().st_size} bytes)")


def _load_tsv(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def _load_durations(path: Path) -> dict[str, float]:
    out = {}
    with path.open(encoding="utf-8") as f:
        next(f, None)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                try:
                    out[parts[0]] = float(parts[1])
                except ValueError:
                    continue
    return out


def _ffmpeg_wav(src: Path, dest: Path, sr: int = 16000) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(src),
        "-ac",
        "1",
        "-ar",
        str(sr),
        "-c:a",
        "pcm_s16le",
        str(dest),
    ]
    subprocess.check_call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def select_talkers(rows: list[dict], durs: dict[str, float], rng: random.Random) -> dict:
    by: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        sent = (row.get("sentence") or "").strip()
        if not sent:
            continue
        path = row["path"]
        ms = durs.get(path)
        if ms is None or ms < MIN_MS or ms > MAX_MS:
            continue
        try:
            up = int(row.get("up_votes") or 0)
        except ValueError:
            up = 0
        if up < 1:
            continue
        rec = {
            "client_id": row["client_id"],
            "path": path,
            "sentence": sent,
            "duration_ms": int(ms),
            "up_votes": up,
            "gender": row.get("gender") or "",
        }
        by[row["client_id"]].append(rec)
    for clips in by.values():
        clips.sort(key=lambda c: (-c["duration_ms"], c["path"]))

    mains = [cid for cid, clips in by.items() if len(clips) >= MAIN_CLIPS]
    if not mains:
        raise SystemExit("no Common Voice talker with ≥3 clips in duration window")
    mains.sort()
    rng.shuffle(mains)
    main_id = mains[0]
    others = [cid for cid in by if cid != main_id]
    others.sort()
    rng.shuffle(others)
    if len(others) < N_TALKERS - 1:
        raise SystemExit(
            f"only {len(others) + 1} distinct talkers after filters; need {N_TALKERS}"
        )
    chosen = [main_id] + others[: N_TALKERS - 1]
    talkers = []
    for i, cid in enumerate(chosen):
        clips = by[cid]
        n = MAIN_CLIPS if i == 0 else 1
        talkers.append(
            {
                "speaker_id": f"speaker_{i + 1}",
                "stream_id": f"s{i + 1:02d}",
                "role": "main" if i == 0 else "babble",
                "client_id": cid,
                "gender": clips[0].get("gender") or "",
                "clips": clips[:n],
            }
        )
    needed = [c["path"] for t in talkers for c in t["clips"]]
    return {
        "corpus": "Mozilla Common Voice 17.0 ru (test split)",
        "license": "CC0-1.0",
        "source": "fsicoli/common_voice_17_0 (packaging of commonvoice.mozilla.org)",
        "seed": SEED,
        "n_talkers": len(talkers),
        "n_test_speakers_total": len({r["client_id"] for r in rows}),
        "n_filtered_speakers": len(by),
        "talkers": talkers,
        "needed_mp3": needed,
    }


def extract_mp3s(tar_path: Path, names: list[str], dest_dir: Path) -> None:
    dest_dir.mkdir(parents=True, exist_ok=True)
    want = set(names)
    found = set()
    with tarfile.open(tar_path) as tf:
        for m in tf.getmembers():
            base = Path(m.name).name
            if base not in want:
                continue
            out = dest_dir / base
            if out.is_file() and out.stat().st_size > 1000:
                found.add(base)
                continue
            src = tf.extractfile(m)
            if src is None:
                continue
            out.write_bytes(src.read())
            found.add(base)
    missing = [n for n in names if n not in found]
    if missing:
        raise SystemExit(f"tar missing {len(missing)} clips e.g. {missing[:3]}")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, default=CORPUS)
    p.add_argument("--tar", type=Path, default=None)
    p.add_argument("--seed", type=int, default=SEED)
    args = p.parse_args()
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    tsv = out / "test.tsv"
    dur_path = out / "clip_durations.tsv"
    _download(f"{HF}/transcript/ru/test.tsv", tsv)
    _download(f"{HF}/transcript/ru/clip_durations.tsv", dur_path)
    rows = _load_tsv(tsv)
    durs = _load_durations(dur_path)
    rng = random.Random(args.seed)
    sel = select_talkers(rows, durs, rng)
    (out / "selection.json").write_text(json.dumps(sel, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"selected {sel['n_talkers']} talkers from {sel['n_filtered_speakers']} filtered / {sel['n_test_speakers_total']} test speakers")

    tar = args.tar
    if tar is None:
        env = os.environ.get("CV17_TAR")
        candidates = [
            Path(env) if env else None,
            Path("/tmp/cv17-ru/audio/ru_test_0.tar"),
            out / "ru_test_0.tar",
        ]
        tar = next((c for c in candidates if c is not None and c.is_file()), None)
    if tar is None:
        tar = out / "ru_test_0.tar"
        _download(f"{HF}/audio/ru/test/ru_test_0.tar", tar)
    else:
        print(f"using tar {tar}")

    mp3_dir = out / "mp3"
    extract_mp3s(tar, sel["needed_mp3"], mp3_dir)
    wav_dir = out / "wav"
    if not shutil.which("ffmpeg"):
        print("need ffmpeg to decode mp3 → 16 kHz wav", file=sys.stderr)
        return 2
    for name in sel["needed_mp3"]:
        wav = wav_dir / (Path(name).stem + ".wav")
        if not wav.is_file():
            _ffmpeg_wav(mp3_dir / name, wav)
    print(f"decoded {len(sel['needed_mp3'])} wavs under {wav_dir}")
    print(f"selection: {out / 'selection.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
